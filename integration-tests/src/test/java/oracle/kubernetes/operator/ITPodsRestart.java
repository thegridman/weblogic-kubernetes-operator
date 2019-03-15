// Copyright 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.util.Map;
import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Simple JUnit test file used for testing Operator.
 *
 * <p>This test is used for testing pods being restarted by some properties change.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ITPodsRestart extends BaseTest {

  private static Domain domain = null;
  private static Operator operator1;

  /**
   * This method gets called only once before any of the test methods are executed. It does the
   * initialization of the integration test properties defined in OperatorIT.properties and setting
   * the resultRoot, pvRoot and projectRoot attributes. Create Operator1 and domainOnPVUsingWLST
   * with admin server and 1 managed server if they are not running
   *
   * @throws Exception
   */
  @BeforeClass
  public static void staticPrepare() throws Exception {
    // initialize test properties and create the directories
    if (!QUICKTEST) {
      initialize(APP_PROPS_FILE);

      logger.info("Checking if operator1 and domain are running, if not creating");
      if (operator1 == null) {
        operator1 = TestUtils.createOperator(OPERATOR1_YAML);
      }

      domain = createPodsRestartdomain();
      Assert.assertNotNull(domain);
    }
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories
   *
   * @throws Exception
   */
  @AfterClass
  public static void staticUnPrepare() throws Exception {
    if (!QUICKTEST) {
      logger.info("+++++++++++++++++++++++++++++++++---------------------------------+");
      logger.info("BEGIN");
      logger.info("Run once, release cluster lease");

      destroyPodsRestartdomain();
      tearDown();

      logger.info("SUCCESS");
    }
  }

  /**
   * The properties tested is: env: "-Dweblogic.StdoutDebugEnabled=false"-->
   * "-Dweblogic.StdoutDebugEnabled=true"
   *
   * @throws Exception
   */
  @Test
  public void testServerPodsRestartByChangingEnvProperty() throws Exception {
    Assume.assumeFalse(QUICKTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    boolean testCompletedSuccessfully = false;
    logger.info(
        "About to testDomainServerRestart for Domain: "
            + domain.getDomainUid()
            + "  env property: StdoutDebugEnabled=false to StdoutDebugEnabled=true");
    domain.testDomainServerRestart(
        "\"-Dweblogic.StdoutDebugEnabled=false\"", "\"-Dweblogic.StdoutDebugEnabled=true\"");

    logger.info("SUCCESS - " + testMethodName);
  }

  /**
   * The properties tested is: logHomeEnabled: true --> logHomeEnabled: false
   *
   * @throws Exception
   */
  @Test
  public void testServerPodsRestartByChangingLogHomeEnabled() throws Exception {
    Assume.assumeFalse(QUICKTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    boolean testCompletedSuccessfully = false;

    logger.info(
        "About to testDomainServerRestart for Domain: "
            + domain.getDomainUid()
            + "  logHomeEnabled: true -->  logHomeEnabled: false");
    domain.testDomainServerRestart("logHomeEnabled: true", "logHomeEnabled: false");

    logger.info("SUCCESS - " + testMethodName);
  }

  /**
   * The properties tested is: imagePullPolicy: IfNotPresent --> imagePullPolicy: Never
   *
   * @throws Exception
   */
  @Test
  public void testServerPodsRestartByChangingImagePullPolicy() throws Exception {
    Assume.assumeFalse(QUICKTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    boolean testCompletedSuccessfully = false;

    logger.info(
        "About to testDomainServerRestart for Domain: "
            + domain.getDomainUid()
            + " imagePullPolicy: IfNotPresent -->  imagePullPolicy: Never ");
    domain.testDomainServerRestart(
        "imagePullPolicy: \"IfNotPresent\"", "imagePullPolicy: \"Never\" ");

    logger.info("SUCCESS - " + testMethodName);
  }

  /**
   * The properties tested is: includeServerOutInPodLog: true --> includeServerOutInPodLog: false
   *
   * @throws Exception
   */
  @Test
  public void testServerPodsRestartByChangingIncludeServerOutInPodLog() throws Exception {
    Assume.assumeFalse(QUICKTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    boolean testCompletedSuccessfully = false;

    logger.info(
        "About to testDomainServerRestart for Domain: "
            + domain.getDomainUid()
            + "  includeServerOutInPodLog: true -->  includeServerOutInPodLog: false");
    domain.testDomainServerRestart(
        "includeServerOutInPodLog: true", "includeServerOutInPodLog: false");

    logger.info("SUCCESS - " + testMethodName);
  }

  /**
   * The properties tested is: image: "store/oracle/weblogic:12.2.1.3" --> image:
   * "store/oracle/weblogic:duplicate"
   *
   * @throws Exception
   */
  @Test
  public void testServerPodsRestartByChangingImage() throws Exception {
    Assume.assumeFalse(QUICKTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    boolean testCompletedSuccessfully = false;
    try {
      logger.info(
          "About to testDomainServerRestart for Domain: "
              + domain.getDomainUid()
              + "  Image property: store/oracle/weblogic:12.2.1.3 to store/oracle/weblogic:duplicate");
      TestUtils.dockerTagImage("store/oracle/weblogic:12.2.1.3", "store/oracle/weblogic:duplicate");
      domain.testDomainServerRestart(
          "\"store/oracle/weblogic:12.2.1.3\"", "\"store/oracle/weblogic:duplicate\"");
    } finally {
      TestUtils.dockerRemoveImage("store/oracle/weblogic:duplicate");
    }

    logger.info("SUCCESS - " + testMethodName);
  }

  private static Domain createPodsRestartdomain() throws Exception {

    Map<String, Object> domainMap = TestUtils.loadYaml(DOMAINONPV_WLST_YAML);
    domainMap.put("domainUID", "domainpodsrestart");
    domainMap.put("adminNodePort", new Integer("30707"));
    domainMap.put("t3ChannelPort", new Integer("30081"));
    domainMap.put("initialManagedServerReplicas", new Integer("1"));

    logger.info("Creating Domain domain& verifing the domain creation");
    domain = TestUtils.createDomain(domainMap);
    domain.verifyDomainCreated();

    return domain;
  }

  private static void destroyPodsRestartdomain() throws Exception {
    if (domain != null) {
      domain.destroy();
    }
  }
}
