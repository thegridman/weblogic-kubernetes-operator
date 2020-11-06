// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Event;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ObjectReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1SubjectRulesReviewStatus;
import io.kubernetes.client.util.Watch;
import oracle.kubernetes.operator.TuningParameters.MainTuning;
import oracle.kubernetes.operator.calls.FailureStatusSourceException;
import oracle.kubernetes.operator.helpers.ConfigMapHelper;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.DomainValidationSteps;
import oracle.kubernetes.operator.helpers.JobHelper;
import oracle.kubernetes.operator.helpers.KubernetesUtils;
import oracle.kubernetes.operator.helpers.PodHelper;
import oracle.kubernetes.operator.helpers.ServiceHelper;
import oracle.kubernetes.operator.logging.LoggingContext;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.LoggingFilter;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.logging.OncePerMessageLoggingFilter;
import oracle.kubernetes.operator.steps.BeforeAdminServiceStep;
import oracle.kubernetes.operator.steps.DeleteDomainStep;
import oracle.kubernetes.operator.steps.DomainPresenceStep;
import oracle.kubernetes.operator.steps.ManagedServersUpStep;
import oracle.kubernetes.operator.steps.WatchPodReadyAdminStep;
import oracle.kubernetes.operator.work.Component;
import oracle.kubernetes.operator.work.Fiber;
import oracle.kubernetes.operator.work.Fiber.CompletionCallback;
import oracle.kubernetes.operator.work.FiberGate;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.operator.work.Step.StepAndPacket;
import oracle.kubernetes.weblogic.domain.model.Configuration;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainSpec;
import oracle.kubernetes.weblogic.domain.model.DomainStatus;
import oracle.kubernetes.weblogic.domain.model.Model;
import oracle.kubernetes.weblogic.domain.model.OnlineUpdate;
import oracle.kubernetes.weblogic.domain.model.ServerHealth;
import oracle.kubernetes.weblogic.domain.model.ServerStatus;

import static oracle.kubernetes.operator.LabelConstants.INTROSPECTION_STATE_LABEL;
import static oracle.kubernetes.operator.ProcessingConstants.DOMAIN_INTROSPECT_REQUESTED;
import static oracle.kubernetes.operator.ProcessingConstants.MAKE_RIGHT_DOMAIN_OPERATION;
import static oracle.kubernetes.operator.ProcessingConstants.SERVER_HEALTH_MAP;
import static oracle.kubernetes.operator.ProcessingConstants.SERVER_STATE_MAP;
import static oracle.kubernetes.operator.helpers.LegalNames.toJobIntrospectorName;

public class DomainProcessorImpl implements DomainProcessor {

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private static final Map<String, FiberGate> makeRightFiberGates = new ConcurrentHashMap<>();
  private static final Map<String, FiberGate> statusFiberGates = new ConcurrentHashMap<>();

  @SuppressWarnings("FieldMayBeFinal") // Map namespace to map of domainUID to Domain; tests may replace this value.
  private static Map<String, Map<String, DomainPresenceInfo>> DOMAINS = new ConcurrentHashMap<>();
  private static final Map<String, Map<String, ScheduledFuture<?>>> statusUpdaters = new ConcurrentHashMap<>();
  private final DomainProcessorDelegate delegate;

  public DomainProcessorImpl(DomainProcessorDelegate delegate) {
    this.delegate = delegate;
  }

  private static DomainPresenceInfo getExistingDomainPresenceInfo(String ns, String domainUid) {
    return DOMAINS.computeIfAbsent(ns, k -> new ConcurrentHashMap<>()).get(domainUid);
  }

  static void registerDomainPresenceInfo(DomainPresenceInfo info) {
    DOMAINS
          .computeIfAbsent(info.getNamespace(), k -> new ConcurrentHashMap<>())
          .put(info.getDomainUid(), info);
  }

  private static void unregisterPresenceInfo(String ns, String domainUid) {
    Map<String, DomainPresenceInfo> map = DOMAINS.get(ns);
    if (map != null) {
      map.remove(domainUid);
    }
  }

  private static void registerStatusUpdater(
        String ns, String domainUid, ScheduledFuture<?> future) {
    ScheduledFuture<?> existing =
          statusUpdaters.computeIfAbsent(ns, k -> new ConcurrentHashMap<>()).put(domainUid, future);
    if (existing != null) {
      existing.cancel(false);
    }
  }

  private static void unregisterStatusUpdater(String ns, String domainUid) {
    Map<String, ScheduledFuture<?>> map = statusUpdaters.get(ns);
    if (map != null) {
      ScheduledFuture<?> existing = map.remove(domainUid);
      if (existing != null) {
        existing.cancel(true);
      }
    }
  }

  private static void onEvent(V1Event event) {
    V1ObjectReference ref = event.getInvolvedObject();
    if (ref == null || ref.getName() == null) {
      return;
    }

    String[] domainAndServer = ref.getName().split("-");
    String domainUid = domainAndServer[0];
    String serverName = domainAndServer[1];
    String status = getReadinessStatus(event);
    if (status == null) {
      return;
    }

    Optional.ofNullable(DOMAINS.get(event.getMetadata().getNamespace()))
          .map(m -> m.get(domainUid))
          .ifPresent(info -> info.updateLastKnownServerStatus(serverName, status));
  }

  private static String getReadinessStatus(V1Event event) {
    return Optional.ofNullable(event.getMessage())
          .filter(m -> m.contains(WebLogicConstants.READINESS_PROBE_NOT_READY_STATE))
          .map(m -> m.substring(m.lastIndexOf(':') + 1).trim())
          .orElse(null);
  }

  // pre-conditions: DomainPresenceInfo SPI
  // "principal"
  static Step bringAdminServerUp(
        DomainPresenceInfo info, PodAwaiterStepFactory podAwaiterStepFactory) {
    return bringAdminServerUpSteps(info, podAwaiterStepFactory);
  }

  private static Step domainIntrospectionSteps(DomainPresenceInfo info) {
    return Step.chain(
          ConfigMapHelper.readIntrospectionVersionStep(info.getNamespace(), info.getDomainUid()),
          new IntrospectionRequestStep(info),
          JobHelper.deleteDomainIntrospectorJobStep(null),
          JobHelper.createDomainIntrospectorJobStep(null));
  }

  private static class IntrospectionRequestStep extends Step {

    private final String requestedIntrospectVersion;

    public IntrospectionRequestStep(DomainPresenceInfo info) {
      this.requestedIntrospectVersion = info.getDomain().getIntrospectVersion();
    }

    @Override
    public NextAction apply(Packet packet) {
      if (!Objects.equals(requestedIntrospectVersion, packet.get(INTROSPECTION_STATE_LABEL))) {
        packet.put(DOMAIN_INTROSPECT_REQUESTED, Optional.ofNullable(requestedIntrospectVersion).orElse("0"));
      }
      return doNext(packet);
    }
  }

  private static Step bringAdminServerUpSteps(
        DomainPresenceInfo info, PodAwaiterStepFactory podAwaiterStepFactory) {
    List<Step> steps = new ArrayList<>();
    steps.add(new BeforeAdminServiceStep(null));
    steps.add(PodHelper.createAdminPodStep(null));

    if (Domain.isExternalServiceConfigured(info.getDomain().getSpec())) {
      steps.add(ServiceHelper.createForExternalServiceStep(null));
    }
    steps.add(ServiceHelper.createForServerStep(null));
    steps.add(new WatchPodReadyAdminStep(podAwaiterStepFactory, null));
    return Step.chain(steps.toArray(new Step[0]));
  }

  private static Step bringManagedServersUp(Step next) {
    return new ManagedServersUpStep(next);
  }

  private FiberGate getMakeRightFiberGate(String ns) {
    return makeRightFiberGates.computeIfAbsent(ns, k -> delegate.createFiberGate());
  }

  private FiberGate getStatusFiberGate(String ns) {
    return statusFiberGates.computeIfAbsent(ns, k -> delegate.createFiberGate());
  }

  /**
   * Stop namespace.
   * @param ns namespace
   */
  public void stopNamespace(String ns) {
    try (LoggingContext stack = LoggingContext.setThreadContext().namespace(ns)) {
      Map<String, DomainPresenceInfo> map = DOMAINS.get(ns);
      if (map != null) {
        for (DomainPresenceInfo dpi : map.values()) {
          stack.domainUid(dpi.getDomainUid());

          Domain dom = dpi.getDomain();
          DomainPresenceInfo value =
              (dom != null)
                  ? new DomainPresenceInfo(dom)
                  : new DomainPresenceInfo(dpi.getNamespace(), dpi.getDomainUid());
          value.setDeleting(true);
          value.setPopulated(true);
          createMakeRightOperation(value).withExplicitRecheck().forDeletion().execute();
        }
      }
    }
  }

  /**
   * Report on currently suspended fibers. This is the first step toward diagnosing if we need special handling
   * to kill or kick these fibers.
   */
  public void reportSuspendedFibers() {
    if (LOGGER.isFineEnabled()) {
      BiConsumer<String, FiberGate> consumer =
          (namespace, gate) -> {
            gate.getCurrentFibers().forEach(
                (key, fiber) -> {
                  Optional.ofNullable(fiber.getSuspendedStep()).ifPresent(suspendedStep -> {
                    try (LoggingContext ignored
                             = LoggingContext.setThreadContext().namespace(namespace).domainUid(getDomainUid(fiber))) {
                      LOGGER.fine("Fiber is SUSPENDED at " + suspendedStep.getName());
                    }
                  });
                });
          };
      makeRightFiberGates.forEach(consumer);
      statusFiberGates.forEach(consumer);
    }
  }

  private String getDomainUid(Fiber fiber) {
    return Optional.ofNullable(fiber)
          .map(Fiber::getPacket)
          .map(p -> p.getSpi(DomainPresenceInfo.class))
          .map(DomainPresenceInfo::getDomainUid).orElse("");
  }

  /**
   * Dispatch pod watch event.
   * @param item watch event
   */
  public void dispatchPodWatch(Watch.Response<V1Pod> item) {
    if (getPodLabel(item.object, LabelConstants.DOMAINUID_LABEL) == null) {
      return;
    }

    if (getPodLabel(item.object, LabelConstants.SERVERNAME_LABEL) != null) {
      processServerPodWatch(item.object, item.type);
    } else if (getPodLabel(item.object, LabelConstants.JOBNAME_LABEL) != null) {
      processIntrospectorJobPodWatch(item.object, item.type);
    }
  }

  private void processServerPodWatch(V1Pod pod, String watchType) {
    String domainUid = getPodLabel(pod, LabelConstants.DOMAINUID_LABEL);
    DomainPresenceInfo info = getExistingDomainPresenceInfo(getNamespace(pod), domainUid);
    if (info == null) {
      return;
    }

    String serverName = getPodLabel(pod, LabelConstants.SERVERNAME_LABEL);
    switch (watchType) {
      case "ADDED":
        info.setServerPodBeingDeleted(serverName, Boolean.FALSE);
        // fall through
      case "MODIFIED":
        info.setServerPodFromEvent(serverName, pod);
        break;
      case "DELETED":
        boolean removed = info.deleteServerPodFromEvent(serverName, pod);
        if (removed && info.isNotDeleting() && !info.isServerPodBeingDeleted(serverName)) {
          LOGGER.info(MessageKeys.POD_DELETED, domainUid, getNamespace(pod), serverName);
          createMakeRightOperation(info).interrupt().withExplicitRecheck().execute();
        }
        break;

      case "ERROR":
      default:
    }
  }

  private String getNamespace(V1Pod pod) {
    return Optional.ofNullable(pod)
        .map(V1Pod::getMetadata)
        .map(V1ObjectMeta::getNamespace)
        .orElse(null);
  }

  private String getPodLabel(V1Pod pod, String labelName) {
    return Optional.ofNullable(pod)
        .map(V1Pod::getMetadata)
        .map(V1ObjectMeta::getLabels)
        .map(m -> m.get(labelName))
        .orElse(null);
  }

  private void processIntrospectorJobPodWatch(V1Pod pod, String watchType) {
    String domainUid = getPodLabel(pod, LabelConstants.DOMAINUID_LABEL);
    DomainPresenceInfo info = getExistingDomainPresenceInfo(getNamespace(pod), domainUid);
    if (info == null) {
      return;
    }

    switch (watchType) {
      case "ADDED":
      case "MODIFIED":
        PodWatcher.PodStatus podStatus = PodWatcher.getPodStatus(pod);
        new DomainStatusUpdate(pod, domainUid, delegate, info, podStatus).invoke();
        break;
      default:
    }
  }

  /* Recently, we've seen a number of intermittent bugs where K8s reports
   * outdated watch events.  There seem to be two main cases: 1) a DELETED
   * event for a resource that was deleted, but has since been recreated, and 2)
   * a MODIFIED event for an object that has already had subsequent modifications.
   */

  /**
   * Dispatch service watch event.
   * @param item watch event
   */
  public void dispatchServiceWatch(Watch.Response<V1Service> item) {
    V1Service service = item.object;
    String domainUid = ServiceHelper.getServiceDomainUid(service);
    if (domainUid == null) {
      return;
    }

    DomainPresenceInfo info =
        getExistingDomainPresenceInfo(service.getMetadata().getNamespace(), domainUid);
    if (info == null) {
      return;
    }

    switch (item.type) {
      case "ADDED":
      case "MODIFIED":
        ServiceHelper.updatePresenceFromEvent(info, item.object);
        break;
      case "DELETED":
        boolean removed = ServiceHelper.deleteFromEvent(info, item.object);
        if (removed && info.isNotDeleting()) {
          createMakeRightOperation(info).interrupt().withExplicitRecheck().execute();
        }
        break;
      default:
    }
  }

  /**
   * Dispatch config map watch event.
   * @param item watch event
   */
  public void dispatchConfigMapWatch(Watch.Response<V1ConfigMap> item) {
    V1ConfigMap c = item.object;
    if (c != null && c.getMetadata() != null) {
      switch (item.type) {
        case "MODIFIED":
        case "DELETED":
          delegate.runSteps(
              ConfigMapHelper.createScriptConfigMapStep(
                    c.getMetadata().getNamespace()));
          break;

        case "ERROR":
        default:
      }
    }
  }

  /**
   * Dispatch event watch event.
   * @param item watch event
   */
  public void dispatchEventWatch(Watch.Response<V1Event> item) {
    V1Event e = item.object;
    if (e != null) {
      switch (item.type) {
        case "ADDED":
        case "MODIFIED":
          onEvent(e);
          break;
        case "DELETED":
        case "ERROR":
        default:
      }
    }
  }

  /**
   * Dispatch the Domain event to the appropriate handler.
   *
   * @param item An item received from a Watch response.
   */
  public void dispatchDomainWatch(Watch.Response<Domain> item) {
    switch (item.type) {
      case "ADDED":
        handleAddedDomain(item.object);
        break;
      case "MODIFIED":
        handleModifiedDomain(item.object);
        break;
      case "DELETED":
        handleDeletedDomain(item.object);
        break;

      case "ERROR":
      default:
    }
  }

  private void handleAddedDomain(Domain domain) {
    LOGGER.info(MessageKeys.WATCH_DOMAIN, domain.getDomainUid());
    createMakeRightOperation(new DomainPresenceInfo(domain)).interrupt().withExplicitRecheck().execute();
  }

  private void handleModifiedDomain(Domain domain) {
    LOGGER.fine(MessageKeys.WATCH_DOMAIN, domain.getDomainUid());
    createMakeRightOperation(new DomainPresenceInfo(domain)).interrupt().execute();
  }

  private void handleDeletedDomain(Domain domain) {
    LOGGER.info(MessageKeys.WATCH_DOMAIN_DELETED, domain.getDomainUid());
    createMakeRightOperation(new DomainPresenceInfo(domain)).interrupt().forDeletion().withExplicitRecheck().execute();
  }

  private void scheduleDomainStatusUpdating(DomainPresenceInfo info) {
    final OncePerMessageLoggingFilter loggingFilter = new OncePerMessageLoggingFilter();

    MainTuning main = TuningParameters.getInstance().getMainTuning();
    registerStatusUpdater(
        info.getNamespace(),
        info.getDomainUid(),
        delegate.scheduleWithFixedDelay(
            () -> {
              try {
                V1SubjectRulesReviewStatus srrs =
                    delegate.getSubjectRulesReviewStatus(info.getNamespace());
                Packet packet = new Packet();
                packet
                    .getComponents()
                    .put(
                        ProcessingConstants.DOMAIN_COMPONENT_NAME,
                        Component.createFor(
                            info, delegate.getVersion(), V1SubjectRulesReviewStatus.class, srrs));
                packet.put(LoggingFilter.LOGGING_FILTER_PACKET_KEY, loggingFilter);
                Step strategy =
                    ServerStatusReader.createStatusStep(main.statusUpdateTimeoutSeconds, null);
                FiberGate gate = getStatusFiberGate(info.getNamespace());

                Fiber f =
                    gate.startFiberIfNoCurrentFiber(
                        info.getDomainUid(),
                        strategy,
                        packet,
                        new CompletionCallback() {
                          @Override
                          public void onCompletion(Packet packet) {
                            AtomicInteger serverHealthRead =
                                packet.getValue(
                                    ProcessingConstants.REMAINING_SERVERS_HEALTH_TO_READ);
                            if (serverHealthRead == null || serverHealthRead.get() == 0) {
                              loggingFilter.setFiltering(false).resetLogHistory();
                            } else {
                              loggingFilter.setFiltering(true);
                            }
                          }

                          @Override
                          public void onThrowable(Packet packet, Throwable throwable) {
                            logThrowable(throwable);
                            loggingFilter.setFiltering(true);
                          }
                        });
              } catch (Throwable t) {
                try (LoggingContext ignored
                         = LoggingContext.setThreadContext()
                    .namespace(info.getNamespace()).domainUid(info.getDomainUid())) {
                  LOGGER.severe(MessageKeys.EXCEPTION, t);
                }
              }
            },
            main.initialShortDelay,
            main.initialShortDelay,
            TimeUnit.SECONDS));
  }

  private void logThrowable(Throwable throwable) {
    if (throwable instanceof Step.MultiThrowable) {
      for (Throwable t : ((Step.MultiThrowable) throwable).getThrowables()) {
        logThrowable(t);
      }
    } else if (throwable instanceof FailureStatusSourceException) {
      ((FailureStatusSourceException) throwable).log();
    } else {
      LOGGER.severe(MessageKeys.EXCEPTION, throwable);
    }
  }

  @Override
  public MakeRightDomainOperationImpl createMakeRightOperation(DomainPresenceInfo liveInfo) {
    return new MakeRightDomainOperationImpl(liveInfo);
  }

  @Override
  public MakeRightDomainOperation createMakeRightOperation(Domain liveDomain) {
    return createMakeRightOperation(new DomainPresenceInfo(liveDomain));
  }

  public Step createPopulatePacketServerMapsStep(oracle.kubernetes.operator.work.Step next) {
    return new PopulatePacketServerMapsStep(next);
  }

  public static class PopulatePacketServerMapsStep extends Step {
    public PopulatePacketServerMapsStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      populatePacketServerMapsFromDomain(packet);
      return doNext(packet);
    }

    private void populatePacketServerMapsFromDomain(Packet packet) {
      Map<String, ServerHealth> serverHealth = new ConcurrentHashMap<>();
      Map<String, String> serverState = new ConcurrentHashMap<>();
      Optional.ofNullable(packet.getSpi(DomainPresenceInfo.class))
          .map(DomainPresenceInfo::getDomain)
          .map(Domain::getStatus)
          .map(DomainStatus::getServers)
          .ifPresent(servers -> servers.forEach(item -> addServerToMaps(serverHealth, serverState, item)));
      if (!serverState.isEmpty()) {
        packet.put(SERVER_STATE_MAP, serverState);
      }
      if (!serverHealth.isEmpty()) {
        packet.put(SERVER_HEALTH_MAP, serverHealth);
      }
    }

    private void addServerToMaps(Map<String, ServerHealth> serverHealthMap,
                                 Map<String, String> serverStateMap, ServerStatus item) {
      if (item.getHealth() != null) {
        serverHealthMap.put(item.getServerName(), item.getHealth());
      }
      if (item.getState() != null) {
        serverStateMap.put(item.getServerName(), item.getState());
      }
    }

  }

  /**
   * A factory which creates and executes steps to align the cached domain status with the value read from Kubernetes.
   */
  class MakeRightDomainOperationImpl implements MakeRightDomainOperation {

    private final DomainPresenceInfo liveInfo;
    private boolean explicitRecheck;
    private boolean deleting;
    private boolean willInterrupt;
    private boolean inspectionRun;

    /**
     * Create the operation.
     * @param liveInfo domain presence info read from Kubernetes
     */
    MakeRightDomainOperationImpl(DomainPresenceInfo liveInfo) {
      this.liveInfo = liveInfo;
    }

    /**
     * Modifies the factory to run even if the domain spec is unchanged.
     * @return the updated factory
     */
    @Override
    public MakeRightDomainOperation withExplicitRecheck() {
      explicitRecheck = true;
      return this;
    }

    /**
     * Modifies the factory to handle shutting down the domain.
     * @return the updated factory
     */
    @Override
    public MakeRightDomainOperation forDeletion() {
      deleting = true;
      return this;
    }

    /**
     * Modifies the factory to handle shutting down the domain if the 'deleting' flag is set.
     * @param deleting if true, indicates that the domain is being shut down
     * @return the updated factory
     */
    @Override
    public MakeRightDomainOperation withDeleting(boolean deleting) {
      this.deleting = deleting;
      return this;
    }

    /**
     * Modifies the factory to indicate that it should interrupt any current make-right thread.
     * @return the updated factory
     */
    @Override
    public MakeRightDomainOperation interrupt() {
      willInterrupt = true;
      return this;
    }

    @Override
    public void execute() {
      try (LoggingContext ignored = LoggingContext.setThreadContext().presenceInfo(liveInfo)) {
        if (!delegate.isNamespaceRunning(getNamespace())) {
          return;
        }

        if (isShouldContinue()) {
          internalMakeRightDomainPresence();
        } else {
          LOGGER.fine(MessageKeys.NOT_STARTING_DOMAINUID_THREAD, getDomainUid());
        }
      }
    }

    @Override
    public void setInspectionRun() {
      inspectionRun = true;
    }

    @Override
    public boolean wasInspectionRun() {
      return inspectionRun;
    }

    private boolean isShouldContinue() {
      DomainPresenceInfo cachedInfo = getExistingDomainPresenceInfo(getNamespace(), getDomainUid());
      int currentIntrospectFailureRetryCount = Optional.ofNullable(liveInfo)
          .map(DomainPresenceInfo::getDomain)
          .map(Domain::getStatus)
          .map(DomainStatus::getIntrospectJobFailureCount)
          .orElse(0);

      String existingError = Optional.ofNullable(liveInfo)
          .map(DomainPresenceInfo::getDomain)
          .map(Domain::getStatus)
          .map(DomainStatus::getMessage)
          .orElse(null);

      boolean exceededFailureRetryCount = (currentIntrospectFailureRetryCount
          >= DomainPresence.getDomainPresenceFailureRetryMaxCount());

      boolean isVersionsChanged = isImgRestartIntrospectVerChanged(liveInfo, cachedInfo);

      if (cachedInfo == null || cachedInfo.getDomain() == null) {
        return true;
      } else if (exceededFailureRetryCount && !isVersionsChanged) {
        LOGGER.fine("Stop introspection retry - exceeded configured domainPresenceFailureRetryMaxCount: "
            + DomainPresence.getDomainPresenceFailureRetryMaxCount()
            + " The domainPresenceFailureRetryMaxCount is an operator tuning parameter and can be controlled"
            + " by adding it to the weblogic-operator-cm configmap.");
        return false;
      } else if (existingError != null && existingError.contains("FatalIntrospectorError")) {
        LOGGER.fine("Stop introspection retry - MII Fatal Error: "
            + existingError);
        return false;
      } else if (isCachedInfoNewer(liveInfo, cachedInfo)) {
        return false;  // we have already cached this
      } else if (explicitRecheck || isSpecChanged(liveInfo, cachedInfo)) {
        DomainSourceType domainSourceType = Optional.ofNullable(liveInfo)
            .map(DomainPresenceInfo::getDomain)
            .map(Domain::getDomainHomeSourceType)
            .orElse(DomainSourceType.Image);

        // For MII, reset the useOnlineUpdate to false if chnages in the spec involves more than introspectVersion and
        // useOnlineUpdate

        if (domainSourceType.equals(DomainSourceType.FromModel) && !isSpecChgOk4OnlineUpdate(liveInfo, cachedInfo)) {
          LOGGER.info("DomainType is FromModel and Online changes requested in the spec involves more than "
              + "introspectVersion, overridden to use offline");
          Optional.ofNullable(liveInfo)
              .map(DomainPresenceInfo::getDomain)
              .map(Domain::getSpec)
              .map(DomainSpec::getConfiguration)
              .map(Configuration::getModel)
              .map(Model::getOnlineUpdate)
              .ifPresent(o -> o.setEnabled(false));
        }
        if (exceededFailureRetryCount) {
          Optional.ofNullable(liveInfo)
              .map(DomainPresenceInfo::getDomain)
              .map(Domain::getStatus)
              .map(o -> o.resetIntrospectJobFailureCount());
        }

        if (currentIntrospectFailureRetryCount > 0) {
          LOGGER.info(MessageKeys.INTROSPECT_JOB_FAILED_RETRY_COUNT, cachedInfo.getDomain().getDomainUid(),
              currentIntrospectFailureRetryCount,
              DomainPresence.getDomainPresenceFailureRetryMaxCount());
        }

        return true;
      }
      cachedInfo.setDomain(getDomain());
      return false;
    }

    private void internalMakeRightDomainPresence() {
      LOGGER.fine(MessageKeys.PROCESSING_DOMAIN, getDomainUid());

      Packet packet = new Packet();
      packet.put(MAKE_RIGHT_DOMAIN_OPERATION, this);
      packet
          .getComponents()
          .put(
              ProcessingConstants.DOMAIN_COMPONENT_NAME,
              Component.createFor(liveInfo, delegate.getVersion(),
                  PodAwaiterStepFactory.class, delegate.getPodAwaiterStepFactory(getNamespace()),
                  V1SubjectRulesReviewStatus.class, delegate.getSubjectRulesReviewStatus(getNamespace())));
      runDomainPlan(
            getDomain(),
            getDomainUid(),
            getNamespace(),
            createDomainPlanSteps(packet),
            deleting,
            willInterrupt);
    }

    private StepAndPacket createDomainPlanSteps(Packet packet) {
      return new StepAndPacket(
          createPopulatePacketServerMapsStep(createSteps()),
          packet);
    }

    private Domain getDomain() {
      return liveInfo.getDomain();
    }

    private String getDomainUid() {
      return liveInfo.getDomainUid();
    }

    private String getNamespace() {
      return liveInfo.getNamespace();
    }

    @Override
    public Step createSteps() {
      Step strategy =
            new StartPlanStep(liveInfo, deleting ? createDomainDownPlan(liveInfo) : createDomainUpPlan(liveInfo));
      if (deleting || getDomain() == null) {
        return strategy;
      } else {
        return DomainValidationSteps.createDomainValidationSteps(getNamespace(), strategy);
      }
    }
  }

  private static boolean isSpecChanged(DomainPresenceInfo liveInfo, DomainPresenceInfo cachedInfo) {
    // TODO, RJE: now that we are switching to updating domain status using the separate
    // status-specific endpoint, Kubernetes guarantees that changes to the main endpoint
    // will only be for metadata and spec, so we can know that we have an important
    // change just by looking at metadata.generation.
    return Optional.ofNullable(liveInfo.getDomain())
          .map(Domain::getSpec)
          .map(spec -> !spec.equals(cachedInfo.getDomain().getSpec()))
          .orElse(true);
  }

  private static boolean isSpecChgOk4OnlineUpdate(DomainPresenceInfo liveInfo, DomainPresenceInfo cachedInfo) {
    // Returns true if configuration.sepc.useOnlineUdpate = not set or false
    // false is useOnline is true but there are other changes in spec other than the introspectVersion
    boolean isOnlineUpdate = Optional.ofNullable(liveInfo.getDomain())
        .map(Domain::getSpec)
        .map(DomainSpec::getConfiguration)
        .map(Configuration::getModel)
        .map(Model::getOnlineUpdate)
        .map(OnlineUpdate::getEnabled)
        .orElse(false);

    if (isOnlineUpdate) {
      return Optional.ofNullable(liveInfo.getDomain())
          .map(Domain::getSpec)
          .map(spec -> spec.isSpecChangeForOnlineUpdateOnly(cachedInfo.getDomain().getSpec()))
          .orElse(true);
    }
    return true;
  }

  private static boolean isImgRestartIntrospectVerChanged(DomainPresenceInfo liveInfo, DomainPresenceInfo cachedInfo) {
    String liveIntrospectVersion = Optional.ofNullable(liveInfo)
        .map(DomainPresenceInfo::getDomain)
        .map(Domain::getSpec)
        .map(DomainSpec::getIntrospectVersion)
        .orElse(null);

    String cachedIntropectVersion = Optional.ofNullable(cachedInfo)
        .map(DomainPresenceInfo::getDomain)
        .map(Domain::getSpec)
        .map(DomainSpec::getIntrospectVersion)
        .orElse(null);

    if (!Objects.equals(liveIntrospectVersion, cachedIntropectVersion)) {
      return true;
    }

    String liveRestartVersion = Optional.ofNullable(liveInfo)
        .map(DomainPresenceInfo::getDomain)
        .map(Domain::getRestartVersion)
        .orElse(null);

    String cachedRestartVersion = Optional.ofNullable(cachedInfo)
        .map(DomainPresenceInfo::getDomain)
        .map(Domain::getRestartVersion)
        .orElse(null);

    if (!Objects.equals(liveRestartVersion, cachedRestartVersion)) {
      return true;
    }

    String liveIntrospectImage = Optional.ofNullable(liveInfo)
        .map(DomainPresenceInfo::getDomain)
        .map(Domain::getSpec)
        .map(DomainSpec::getImage)
        .orElse(null);

    String cachedIntrospectImage = Optional.ofNullable(cachedInfo)
        .map(DomainPresenceInfo::getDomain)
        .map(Domain::getSpec)
        .map(DomainSpec::getImage)
        .orElse(null);

    if (!Objects.equals(liveIntrospectImage, cachedIntrospectImage)) {
      return true;
    } else {
      return false;
    }
  }

  private static boolean isCachedInfoNewer(DomainPresenceInfo liveInfo, DomainPresenceInfo cachedInfo) {
    return liveInfo.getDomain() != null
        && KubernetesUtils.isFirstNewer(cachedInfo.getDomain().getMetadata(), liveInfo.getDomain().getMetadata());
  }

  @SuppressWarnings("unused")
  private void runDomainPlan(
      Domain dom,
      String domainUid,
      String ns,
      Step.StepAndPacket plan,
      boolean isDeleting,
      boolean isWillInterrupt) {
    FiberGate gate = getMakeRightFiberGate(ns);
    CompletionCallback cc =
        new CompletionCallback() {
          @Override
          public void onCompletion(Packet packet) {
            // no-op
          }

          @Override
          public void onThrowable(Packet packet, Throwable throwable) {
            logThrowable(throwable);

            gate.startFiberIfLastFiberMatches(
                domainUid,
                Fiber.getCurrentIfSet(),
                DomainStatusUpdater.createFailedStep(throwable, null),
                plan.packet,
                new CompletionCallback() {
                  @Override
                  public void onCompletion(Packet packet) {
                    // no-op
                  }

                  @Override
                  public void onThrowable(Packet packet, Throwable throwable) {
                    logThrowable(throwable);
                  }
                });

            gate.getExecutor()
                .schedule(
                    () -> {
                      DomainPresenceInfo existing = getExistingDomainPresenceInfo(ns, domainUid);
                      if (existing != null) {
                        try (LoggingContext ignored =
                                 LoggingContext.setThreadContext().namespace(ns).domainUid(domainUid)) {
                          existing.setPopulated(false);
                          // proceed only if we have not already retried max number of times
                          int retryCount = existing.incrementAndGetFailureCount();
                          LOGGER.fine(
                              "Failure count for DomainPresenceInfo: "
                                  + existing
                                  + " is now: "
                                  + retryCount);
                          if (retryCount <= DomainPresence.getDomainPresenceFailureRetryMaxCount()) {
                            createMakeRightOperation(existing).withDeleting(isDeleting).withExplicitRecheck().execute();
                          } else {
                            LOGGER.severe(
                                MessageKeys.CANNOT_START_DOMAIN_AFTER_MAX_RETRIES,
                                domainUid,
                                ns,
                                DomainPresence.getDomainPresenceFailureRetryMaxCount(),
                                throwable);
                          }
                        }
                      }
                    },
                    DomainPresence.getDomainPresenceFailureRetrySeconds(),
                    TimeUnit.SECONDS);
          }
        };

    if (isWillInterrupt) {
      gate.startFiber(domainUid, plan.step, plan.packet, cc);
    } else {
      gate.startFiberIfNoCurrentFiber(domainUid, plan.step, plan.packet, cc);
    }
  }

  Step createDomainUpPlan(DomainPresenceInfo info) {
    Step managedServerStrategy = bringManagedServersUp(DomainStatusUpdater.createEndProgressingStep(new TailStep()));

    Step domainUpStrategy =
        Step.chain(
            domainIntrospectionSteps(info),
            DomainValidationSteps.createAfterIntrospectValidationSteps(info.getDomainUid()),
            new DomainStatusStep(info, null),
            bringAdminServerUp(info, delegate.getPodAwaiterStepFactory(info.getNamespace())),
            managedServerStrategy);

    return Step.chain(
          createDomainUpInitialStep(info),
          ConfigMapHelper.readExistingIntrospectorConfigMap(info.getNamespace(), info.getDomainUid()),
          DomainPresenceStep.createDomainPresenceStep(info.getDomain(), domainUpStrategy, managedServerStrategy));
  }

  Step createDomainUpInitialStep(DomainPresenceInfo info) {
    return new UpHeadStep(info);
  }

  private Step createDomainDownPlan(DomainPresenceInfo info) {
    String ns = info.getNamespace();
    String domainUid = info.getDomainUid();
    return Step.chain(
        new DownHeadStep(info, ns),
        new DeleteDomainStep(info, ns, domainUid),
        new UnregisterStep(info));
  }

  private static class UnregisterStep extends Step {
    private final DomainPresenceInfo info;

    UnregisterStep(DomainPresenceInfo info) {
      this(info, null);
    }

    UnregisterStep(DomainPresenceInfo info, Step next) {
      super(next);
      this.info = info;
    }

    @Override
    public NextAction apply(Packet packet) {
      unregisterPresenceInfo(info.getNamespace(), info.getDomainUid());
      return doNext(packet);
    }
  }

  private static class TailStep extends Step {

    @Override
    public NextAction apply(Packet packet) {
      packet.getSpi(DomainPresenceInfo.class).complete();
      return doNext(packet);
    }
  }

  private static class StartPlanStep extends Step {
    private final DomainPresenceInfo info;

    StartPlanStep(DomainPresenceInfo info, Step next) {
      super(next);
      this.info = info;
    }

    @Override
    public NextAction apply(Packet packet) {
      registerDomainPresenceInfo(info);

      return doNext(getNextSteps(), packet);
    }

    private Step getNextSteps() {
      if (lookForPodsAndServices()) {
        return Step.chain(getRecordExistingResourcesSteps(), getNext());
      } else {
        return getNext();
      }
    }

    private boolean lookForPodsAndServices() {
      return !info.isPopulated() && info.isNotDeleting();
    }

    private Step getRecordExistingResourcesSteps() {
      NamespacedResources resources = new NamespacedResources(info.getNamespace(), info.getDomainUid());

      resources.addProcessor(new NamespacedResources.Processors() {
        @Override
        Consumer<V1PodList> getPodListProcessing() {
          return list -> list.getItems().forEach(this::addPod);
        }

        private void addPod(V1Pod pod) {
          Optional.ofNullable(PodHelper.getPodServerName(pod)).ifPresent(name -> info.setServerPod(name, pod));
        }

        @Override
        Consumer<V1ServiceList> getServiceListProcessing() {
          return list -> list.getItems().forEach(this::addService);
        }

        private void addService(V1Service service) {
          ServiceHelper.addToPresence(info, service);
        }
      });

      return resources.createListSteps();
    }

  }

  private static class UpHeadStep extends Step {
    private final DomainPresenceInfo info;

    UpHeadStep(DomainPresenceInfo info) {
      this(info, null);
    }

    UpHeadStep(DomainPresenceInfo info, Step next) {
      super(next);
      this.info = info;
    }

    @Override
    public NextAction apply(Packet packet) {
      info.setDeleting(false);
      return doNext(packet);
    }
  }

  private class DomainStatusStep extends Step {
    private final DomainPresenceInfo info;

    DomainStatusStep(DomainPresenceInfo info, Step next) {
      super(next);
      this.info = info;
    }

    @Override
    public NextAction apply(Packet packet) {
      scheduleDomainStatusUpdating(info);
      return doNext(packet);
    }
  }

  private static class DownHeadStep extends Step {
    private final DomainPresenceInfo info;
    private final String ns;

    DownHeadStep(DomainPresenceInfo info, String ns) {
      this(info, ns, null);
    }

    DownHeadStep(DomainPresenceInfo info, String ns, Step next) {
      super(next);
      this.info = info;
      this.ns = ns;
    }

    @Override
    public NextAction apply(Packet packet) {
      info.setDeleting(true);
      unregisterStatusUpdater(ns, info.getDomainUid());
      return doNext(packet);
    }
  }

  private static class DomainStatusUpdate {
    private final V1Pod pod;
    private final String domainUid;
    private DomainProcessorDelegate delegate;
    private DomainPresenceInfo info;
    private PodWatcher.PodStatus podStatus;

    DomainStatusUpdate(V1Pod pod, String domainUid, DomainProcessorDelegate delegate,
                       DomainPresenceInfo info, PodWatcher.PodStatus podStatus) {
      this.pod = pod;
      this.domainUid = domainUid;
      this.delegate = delegate;
      this.info = info;
      this.podStatus = podStatus;
    }

    private void invoke() {
      switch (podStatus) {
        case PHASE_FAILED:
          delegate.runSteps(
                  DomainStatusUpdater.createFailedStep(
                          info, pod.getStatus().getReason(), pod.getStatus().getMessage(), null));
          break;
        case WAITING_NON_NULL_MESSAGE:
          Optional.ofNullable(getMatchingContainerStatus())
                  .map(V1ContainerStatus::getState)
                  .map(V1ContainerState::getWaiting)
                  .ifPresent(waiting ->
                    delegate.runSteps(
                            DomainStatusUpdater.createFailedStep(
                                    info, waiting.getReason(), waiting.getMessage(), null)));
          break;
        case TERMINATED_ERROR_REASON:
          Optional.ofNullable(getMatchingContainerStatus())
                  .map(V1ContainerStatus::getState)
                  .map(V1ContainerState::getTerminated)
                  .ifPresent(terminated -> delegate.runSteps(
                          DomainStatusUpdater.createFailedStep(
                                  info, terminated.getReason(), terminated.getMessage(), null)));
          break;
        case UNSCHEDULABLE:
          Optional.ofNullable(getMatchingPodCondition())
                  .ifPresent(condition ->
                          delegate.runSteps(
                                  DomainStatusUpdater.createFailedStep(
                                          info, condition.getReason(), condition.getMessage(), null)));
          break;
        case SUCCESS:
          Optional.ofNullable(getMatchingContainerStatus())
                  .map(V1ContainerStatus::getState)
                  .map(V1ContainerState::getWaiting)
                  .ifPresent(waiting ->
                          delegate.runSteps(
                                  DomainStatusUpdater.createProgressingStep(
                                          info, waiting.getReason(), false, null)));
          break;
        default:
      }
    }

    private V1ContainerStatus getMatchingContainerStatus() {
      return Optional.ofNullable(pod.getStatus())
              .map(V1PodStatus::getContainerStatuses)
              .flatMap(this::getMatchingContainerStatus)
              .orElse(null);
    }

    private Optional<V1ContainerStatus> getMatchingContainerStatus(Collection<V1ContainerStatus> statuses) {
      return statuses.stream().filter(this::hasInstrospectorJobName).findFirst();
    }

    private V1PodCondition getMatchingPodCondition() {
      return Optional.ofNullable(pod.getStatus())
              .map(V1PodStatus::getConditions)
              .flatMap(this::getPodCondition)
              .orElse(null);
    }

    private Optional<V1PodCondition> getPodCondition(Collection<V1PodCondition> conditions) {
      return conditions.stream().findFirst();
    }

    private boolean hasInstrospectorJobName(V1ContainerStatus s) {
      return toJobIntrospectorName(domainUid).equals(s.getName());
    }
  }
}
