# !/bin/sh
# Copyright (c) 2020, Oracle Corporation and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
#

#
# Function to get server start policy at cluster level
# $1 - Domain resource in json format
# $2 - Name of cluster
# $3 - Return value containing cluster level server start policy
#
function getClusterPolicy {
  local domainJson=$1
  local clusterName=$2
  local __clusterPolicy=$3
  local effectivePolicy=""

  clusterPolicyCmd="(.spec.clusters[] \
    | select (.clusterName == \"${clusterName}\")).serverStartPolicy"
  effectivePolicy=$(echo ${domainJson} | jq "${clusterPolicyCmd}")
  eval $__clusterPolicy=${effectivePolicy}
}

#
# Function to get server start policy at domain level
# $1 - Domain resource in json format
# $2 - Return value containing domain level server start policy
#
function getDomainPolicy {
  local domainJson=$1
  local __domainPolicy=$2
  local effectivePolicy=""

  clusterPolicyCmd=".spec.serverStartPolicy"
  effectivePolicy=$(echo ${domainJson} | jq "${clusterPolicyCmd}")
  eval $__domainPolicy=${effectivePolicy}
}

#
# Function to get effective start policy of server
# $1 - Domain resource in json format
# $2 - Name of server
# $3 - Name of cluster
# $4 - Return value containing effective server start policy
#
function getEffectivePolicy {
  local domainJson=$1
  local serverName=$2
  local clusterName=$3
  local __currentPolicy=$4
  local policyFound=false
  local managedServers=""

  # Get server start policy for this server
  managedServers=$(echo ${domainJson} | jq -cr '(.spec.managedServers)')
  if [ "${managedServers}" != "null" ]; then
    extractPolicyCmd="(.spec.managedServers[] \
      | select (.serverName == \"${serverName}\") | .serverStartPolicy)"
    currentPolicy=$(echo ${domainJson} | jq -r "${extractPolicyCmd}")
    if [[ -n ${currentPolicy} && ${currentPolicy} != "null" ]]; then
      # Start policy is set at server level, return policy
      eval $__currentPolicy="'${currentPolicy}'"
      policyFound=true
    fi
  fi
  if [ "${policyFound}" == 'false' ]; then
    clusterPolicyCmd="(.spec.clusters[] \
      | select (.clusterName == \"${clusterName}\")).serverStartPolicy"
    currentPolicy=$(echo ${domainJson} | jq -r "${clusterPolicyCmd}")
    if [ "${currentPolicy}" == "null" ]; then
      # Start policy is not set at cluster level, check at domain level
      clusterPolicyCmd=".spec.serverStartPolicy"
      currentPolicy=$(echo ${domainJson} | jq -r "${clusterPolicyCmd}")
      if [ "${currentPolicy}" == "null" ]; then
        # Start policy is not set at domain level, default to IF_NEEDED
        currentPolicy=IF_NEEDED
      fi
    fi
    eval $__currentPolicy="'${currentPolicy}'"
  fi
}

#
# Function to get current start policy of server
# $1 - Domain resource in json format
# $2 - Name of server
# $3 - Return value containing current server start policy
#
function getCurrentPolicy {
  local domainJson=$1
  local serverName=$2
  local __currentPolicy=$3
  local currentServerStartPolicy=""

  # Get server start policy for this server
  managedServers=$(echo ${domainJson} | jq -cr '(.spec.managedServers)')
  if [ "${managedServers}" != "null" ]; then
    extractPolicyCmd="(.spec.managedServers[] \
      | select (.serverName == \"${serverName}\") | .serverStartPolicy)"
    currentServerStartPolicy=$(echo ${domainJson} | jq "${extractPolicyCmd}")
  fi
  eval $__currentPolicy=${currentServerStartPolicy}
}

#
# Function to create server start policy patch string
# $1 - Domain resource in json format
# $2 - Name of server whose policy will be patched
# $3 - Policy value 
# $4 - Return value containing server start policy patch string
#
function createServerStartPolicyPatch {
  local domainJson=$1
  local serverName=$2
  local policy=$3
  local __result=$4
  local currentServerStartPolicy=""

  # Get server start policy for this server
  managedServers=$(echo ${domainJson} | jq -cr '(.spec.managedServers)')
  if [ "${managedServers}" != "null" ]; then
    extractPolicyCmd="(.spec.managedServers[] \
      | select (.serverName == \"${serverName}\") | .serverStartPolicy)"
    currentServerStartPolicy=$(echo ${domainJson} | jq "${extractPolicyCmd}")
  fi
  if [ -z "${currentServerStartPolicy}" ]; then
    # Server start policy doesn't exist, add a new policy
    addPolicyCmd=".[.| length] |= . + {\"serverName\":\"${serverName}\", \
      \"serverStartPolicy\":\"${policy}\"}"
    serverStartPolicyPatch=$(echo ${domainJson} | jq .spec.managedServers | jq -c "${addPolicyCmd}")
  else
    # Server start policy exists, replace policy value 
    replacePolicyCmd="(.spec.managedServers[] \
      | select (.serverName == \"${serverName}\") | .serverStartPolicy) |= \"${policy}\""
    servers="(.spec.managedServers)"
    serverStartPolicyPatch=$(echo ${domainJson} | jq "${replacePolicyCmd}" | jq -cr "${servers}")
  fi
  eval $__result="'${serverStartPolicyPatch}'"
}

#
# Function to create patch json string to unset policy and update replica
# $1 - Domain resource in json format
# $2 - Name of server whose policy will be patched
# $3 - String containing replica patch string
# $4 - Return value containing patch json string
#
function createPatchJsonToUnsetPolicyAndUpdateReplica {
  local domainJson=$1
  local serverName=$2
  local replicaPatch=$3
  local __result=$4

  replacePolicyCmd="[(.spec.managedServers[] \
    | select (.serverName != \"${serverName}\"))]"
  serverStartPolicyPatch=$(echo ${domainJson} | jq "${replacePolicyCmd}")
  patchJson="{\"spec\": {\"clusters\": "${replicaPatch}",\"managedServers\": "${serverStartPolicyPatch}"}}"
  eval $__result="'${patchJson}'"
}

#
# Function to create patch json string to unset policy
# $1 - Domain resource in json format
# $2 - Name of server whose policy will be patched
# $3 - Return value containing patch json string
#
function createPatchJsonToUnsetPolicy {
  local domainJson=$1
  local serverName=$2
  local __result=$3

  replacePolicyCmd="[(.spec.managedServers[] \
    | select (.serverName != \"${serverName}\"))]"
  serverStartPolicyPatch=$(echo ${domainJson} | jq "${replacePolicyCmd}")
  patchJson="{\"spec\": {\"managedServers\": "${serverStartPolicyPatch}"}}"
  eval $__result="'${patchJson}'"
}

#
# Function to get sorted list of servers in a cluster.
# The sorted list is created in 'sortedByAlwaysServers' array.
# $1 - Domain resource in json format
# $2 - Name of server 
# $3 - Name of cluster 
# $4 - Indicates if policy of current server would be unset
#
function getSortedListOfServers {
  local domainJson=$1
  local serverName=$2
  local clusterName=$3
  local withPolicy=$4
  local sortedServers=()
  local otherServers=()

  configMap=$(${kubernetesCli} get cm ${domainUid}-weblogic-domain-introspect-cm \
    -n ${domainNamespace} -o json)
  topology=$(echo "${configMap}" | jq '.data["topology.yaml"]')
  jsonTopology=$(python -c \
    'import sys, yaml, json; print json.dumps(yaml.safe_load('"${topology}"'), indent=4)')
  clusterTopology=$(echo ${jsonTopology} | jq -r '.domain | .configuredClusters[] | select (.name == '\"${clusterName}\"')')
  dynaCluster=$(echo ${clusterTopology} | jq .dynamicServersConfig)
  if [ "${dynaCluster}" == "null" ]; then
    # Cluster is a configured cluster, get server names
    servers=($(echo ${clusterTopology} | jq -r .servers[].name))
    # Sort server names in numero lexi order
    IFS=$'\n' sortedServers=($(sed 's/\([0-9]\)/;\1/' <<<"${servers[*]}" | sort -n -t\; -k2,2 | tr -d ';'));
    unset IFS
    clusterSize=${#sortedServers[@]}
  else 
    # Cluster is a dynamic cluster, calculate server names
    prefix=$(echo ${dynaCluster} | jq -r .serverNamePrefix)
    clusterSize=$(echo ${dynaCluster} | jq .dynamicClusterSize) 
    for (( i=1; i<=$clusterSize; i++ )); do
      localServerName=${prefix}$i
      sortedServers+=(${localServerName})
    done
  fi
  # Create arrays of ALWAYS policy servers and other servers
  sortedServersSize=${#sortedServers[@]}
  if [ "${sortedServersSize}" -gt 0 ]; then
    for localServerName in "${sortedServers[@]}"; do
      getEffectivePolicy "${domainJson}" "${localServerName}" "${clusterName}" policy
      # Update policy when server name matches current server and unsetting
      if [[ "${withPolicy}" == "UNSET" && "${serverName}" == "${localServerName}" ]]; then
        policy=UNSET
      fi
      if [ "${policy}" == "ALWAYS" ]; then
        sortedByAlwaysServers+=(${localServerName})
      else
        otherServers+=(${localServerName})
      fi
    done
  fi
  
  # append other servers to the list of servers with always policy
  otherServersSize=${#otherServers[@]}
  if [ "${otherServersSize}" -gt 0 ]; then
    for otherServer in "${otherServers[@]}"; do
      sortedByAlwaysServers+=($otherServer)
    done
  fi
}

#
# Get replica count for a cluster
# $1 - Domain resource in json format
# $2 - Name of cluster 
# $3 - Return value containing replica count
#
function getReplicaCount {
  local domainJson=$1
  local clusterName=$2
  local __replicaCount=$3

  replicasCmd="(.spec.clusters[] \
    | select (.clusterName == \"${clusterName}\")).replicas"
  replicaCount=$(echo ${domainJson} | jq "${replicasCmd}")
  eval $__replicaCount="'${replicaCount}'"

}

#
# Check servers started in a cluster based on server start policy and 
# replica count.
# $1 - Domain resource in json format
# $2 - Name of server 
# $3 - Name of cluster 
# $4 - Indicates if replicas will stay constant, incremented or decremented
# $5 - Indicates if policy of current server will stay same or unset
# $6 - Return value indicating if current server will be started
#
function checkStartedServers {
  local domainJson=$1
  local serverName=$2
  local clusterName=$3
  local withReplicas=$4
  local withPolicy=$5
  local __started=$6
  local localServerName=""
  local policy=""
  local replicaCount=0
  local currentReplicas=0
  local startedServers=()
  local sortedByAlwaysServers=()
  
  # Get sorted list of servers in 'sortedByAlwaysServers' array
  getSortedListOfServers "${domainJson}" "${serverName}" "${clusterName}" "${withPolicy}"
  getReplicaCount "${domainJson}" "${clusterName}" replicaCount
  # Increment or decrement the replica count based on 'withReplicas' input parameter
  if [ "${withReplicas}" == "INCREASED" ]; then
    replicaCount=$((replicaCount+1))
  elif [ "${withReplicas}" == "DECREASED" ]; then
    replicaCount=$((replicaCount-1))
  fi
  sortedByAlwaysSize=${#sortedByAlwaysServers[@]}
  if [ "${sortedByAlwaysSize}" -gt 0 ]; then
    for localServerName in "${sortedByAlwaysServers[@]}"; do
      getEffectivePolicy "${domainJson}" "${localServerName}" "${clusterName}" policy
      # # Update policy when server name matches current server and unsetting
      if [[ "${serverName}" == "${localServerName}" && "${withPolicy}" == "UNSET" ]]; then
        policy=UNSET
      fi
      # check if server should start based on replica count, policy and current replicas
      shouldStart "${currentReplicas}" "${policy}" "${replicaCount}" result
      if [ "${result}" == 'True' ]; then
        # server should start, increment current replicas and add server to list of started servers
        currentReplicas=$((currentReplicas+1))
        startedServers+=(${localServerName})
      fi
    done
  fi
  startedSize=${#startedServers[@]}
  if [ ${startedSize} -gt 0 ]; then
    # check if current server is in the list of started servers
    if checkStringInArray ${serverName} ${startedServers[@]}; then
      eval $__started="true"
      return
    fi
  fi
  eval $__started="false"
}

#
# Function to check if server should start based on policy and current replicas
# $1 - Current number of replicas
# $2 - Server start policy
# $3 - Replica count
# $4 - Return value
#
function shouldStart {
  local currentReplicas=$1
  local policy=$2
  local replicaCount=$3 
  local __result=$4

  if [ "$policy" == "ALWAYS" ]; then
    eval $__result=True
  elif [ "$policy" == "NEVER" ]; then
    eval $__result=False
  elif [ "${currentReplicas}" -lt "${replicaCount}" ]; then
    eval $__result=True
  else 
    eval $__result=False
  fi
}

#
# Function to create patch string for updating replica count
# $1 - Domain resource in json format
# $2 - Name of cluster whose replica count will be patched
# $3 - operatation string indicating whether to increment or decrement count
# $4 - Return value containing replica update patch string
# $5 - Retrun value containing updated replica count
#
function createReplicaPatch {
  local domainJson=$1
  local clusterName=$2
  local operation=$3
  local __result=$4
  local __replicaCount=$5
  local maxReplicas=""
  local errorMessage="@@ ERROR: Maximum number of servers allowed (maxReplica = ${maxReplicas}) \
are already running. Please increase cluster size to start new servers."

  replicasCmd="(.spec.clusters[] \
    | select (.clusterName == \"${clusterName}\")).replicas"
  maxReplicaCmd="(.status.clusters[] | select (.clusterName == \"${clusterName}\")) \
    | .maximumReplicas"
  replica=$(echo ${domainJson} | jq "${replicasCmd}")
  if [[ -z "${replica}" || "${replica}" == "null" ]]; then
    replica=$(echo ${domainJson} | jq .spec.replicas)
  fi
  if [ "${operation}" == "DECREMENT" ]; then
    replica=$((replica-1))
    if [ ${replica} -lt 0 ]; then
      replica=0
    fi
  elif [ "${operation}" == "INCREMENT" ]; then
    replica=$((replica+1))
    maxReplicas=$(echo ${domainJson} | jq "${maxReplicaCmd}")
    if [ ${replica} -gt ${maxReplicas} ]; then
      echo "${errorMessage}"
      eval $__result="MAX_REPLICA_COUNT_EXCEEDED"
      return
    fi
  fi

  cmd="(.spec.clusters[] | select (.clusterName == \"${clusterName}\") \
    | .replicas) |= ${replica}"
  replicaPatch=$(echo ${domainJson} | jq "${cmd}" | jq -cr '(.spec.clusters)')
  eval $__result="'${replicaPatch}'"
  eval $__replicaCount="'${replica}'"
}

#
# Function to validate whether a server belongs to a  cluster or is an independent managed server
# $1 - Domain unique id.
# $2 - Domain namespace.
# $3 - Server name.
# $4 - Return value indicating if server is valid (i.e. if it's part of a cluster or independent server).
# $5 - Retrun value containting cluster name to which this server belongs.
#
function validateServerAndFindCluster {
  local domainUid=$1
  local domainNamespace=$2 
  local serverName=$3
  local __isValidServer=$4
  local __clusterName=$5
  local serverCount=""
  local errorMessage="Server name is outside the range of allowed servers. \
Please make sure server name is correct."

  configMap=$(${kubernetesCli} get cm ${domainUid}-weblogic-domain-introspect-cm \
    -n ${domainNamespace} -o json)
  topology=$(echo "${configMap}" | jq '.data["topology.yaml"]')
  jsonTopology=$(python -c \
    'import sys, yaml, json; print json.dumps(yaml.safe_load('"${topology}"'), indent=4)')
  servers=($(echo $jsonTopology | jq -r '.domain.servers[].name'))
  if  checkStringInArray "${serverName}" "${servers[@]}" ; then
    eval $__clusterName=""
    eval $__isValidServer=true
  else
    dynamicClause=".domain.configuredClusters[] | select (.dynamicServersConfig != null)"
    namePrefixSize=". | {name: .name, prefix:.dynamicServersConfig.serverNamePrefix, \
                 max:.dynamicServersConfig.maxDynamicClusterSize}"
    dynamicClusters=($(echo $jsonTopology | jq "${dynamicClause}" | jq -cr "${namePrefixSize}"))
    dynamicClustersSize=${#dynamicClusters[@]}
    if [ "${dynamicClustersSize}" -gt 0 ]; then
      for dynaClusterNamePrefix in "${dynamicClusters[@]}"; do
        prefix=$(echo ${dynaClusterNamePrefix} | jq -r .prefix)
        if [[ "${serverName}" == "${prefix}"* ]]; then
          maxSize=$(echo ${dynaClusterNamePrefix} | jq -r .max)
          number='^[0-9]+$'
          if [ $(echo "${serverName}" | grep -c -Eo '[0-9]+$') -gt 0 ]; then
            serverCount=$(echo "${serverName}" | grep -Eo '[0-9]+$')
          fi
          if ! [[ $serverCount =~ $number ]] ; then
             echo "error: Server name is not valid for dynamic cluster." 
             exit 1
          fi
          if [ "${serverCount}" -gt "${maxSize}" ]; then
            printError "${errorMessage}"
            exit 1
          fi
          eval $__clusterName="'$(echo ${dynaClusterNamePrefix} | jq -r .name)'"
          eval $__isValidServer=true
          break
        fi
      done
    fi
    staticClause=".domain.configuredClusters[] | select (.dynamicServersConfig == null)"
    nameCmd=" . | {name: .name, serverName: .servers[].name}"
    configuredClusters=($(echo $jsonTopology | jq "${staticClause}" | jq -cr "${nameCmd}"))
    configuredClusterSize=${#configuredClusters[@]}
    if [ "${configuredClusterSize}" -gt 0 ]; then
      for configuredClusterName in "${configuredClusters[@]}"; do
        name=$(echo ${configuredClusterName} | jq -r .serverName)
        if [ "${serverName}" == "${name}" ]; then
          eval $__clusterName="'$(echo ${configuredClusterName} | jq -r .name)'"
          eval $__isValidServer=true
          break
        fi
      done
    fi
  fi
}

#
# Function to validate whether a cluster is valid and part of the domain
# $1 - Domain unique id.
# $2 - Domain namespace.
# $3 - cluster name
# $4 - Retrun value indicating whether cluster name is valid
#
function validateClusterName {
  local domainUid=$1
  local domainNamespace=$2
  local clusterName=$3
  local __isValidCluster=$4

  configMap=$(${kubernetesCli} get cm ${domainUid}-weblogic-domain-introspect-cm \
    -n ${domainNamespace} -o json)
  topology=$(echo "${configMap}" | jq '.data["topology.yaml"]')
  jsonTopology=$(python -c \
    'import sys, yaml, json; print json.dumps(yaml.safe_load('"${topology}"'), indent=4)')
  clusters=($(echo $jsonTopology | jq -cr .domain.configuredClusters[].name))
  if  checkStringInArray "${clusterName}" "${clusters[@]}" ; then
    eval $__isValidCluster=true
  fi
}

#
# check if string passed as first argument is present in array passed as second argument
# $1 - string to check
# $2 - array
checkStringInArray() {
    local str=$1 arr
    shift
    for arr; do
      [[ $str = "$arr" ]] && return
    done
    return 1
}

# try to execute jq to see whether jq is available
function validateJqAvailable {
  if ! [ -x "$(command -v jq)" ]; then
    validationError "jq is not installed"
  fi
}

# try to execute python to see whether python is available
function validatePythonAvailable {
  if ! [ -x "$(command -v python)" ]; then
    validationError "python is not installed"
  fi
}

# try to execute kubernetes cli to see whether cli is available
function validateKubernetesCliAvailable {
  if ! [ -x "$(command -v ${kubernetesCli})" ]; then
    validationError "${kubernetesCli} is not installed"
  fi
}

#
# Function to exit and print an error message
# $1 - text of message
function fail {
  printError $*
  exit 1
}

# Function to print an error message
function printError {
  echo [ERROR] $*
}

#
# Function to note that a validate error has occurred
#
function validationError {
  printError $*
  validateErrors=true
}

#
# Function to cause the script to fail if there were any validation errors
#
function failIfValidationErrors {
  if [ "$validateErrors" = true ]; then
    fail 'The errors listed above must be resolved before the script can continue'
  fi
}
