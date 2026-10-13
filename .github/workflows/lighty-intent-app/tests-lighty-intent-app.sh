#!/bin/bash
#
# Copyright (c) 2026 icarocamelo. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
#
# Smoke test for the lighty-intent-app Helm chart deployed on a real (minikube)
# Kubernetes cluster: proves the two built Docker images (the app + the
# lighty-intent-slm sidecar) actually start in one pod, that the sidecar is
# reachable by the app at localhost (the chart's "same pod, sidecar container"
# design), and that a transport-domain intent can be submitted, validated, and
# dry-run over RESTCONF through the chart's Service. It does not attempt
# approve-intent: that needs a reachable gNMI/A1 target, which this smoke test
# does not stand up - that end-to-end path is already covered by
# IntentAppModuleSmokeTest in the Maven build.

printLine() {
  printf '%.0s-' {1..100}; echo ""
}

RESTCONF_NODE_PORT=30888
MINIKUBE_IP=$(minikube ip)
HTTP_STATUS_CODES=("200" "201" "202" "204")
declare -a test_results

assertHttpStatusCode() {
  printLine
  if [[ "${HTTP_STATUS_CODES[@]}" =~ "$1" ]]; then
    echo -e "HTTP request: $2\nStatus Code: $1\nTest passed\n"
    test_results+=(0)
  else
    echo -e "HTTP request: $2\nStatus Code: $1\nTest failed\n"
    test_results+=(1)
  fi
}

POD_NAME=$(minikube kubectl -- get pods -l app.kubernetes.io/name=lighty-intent-app-helm -o custom-columns=:".metadata.name" | xargs)

printLine
echo "-- Pods and services --"
minikube kubectl -- get pods -o wide
minikube kubectl -- get services

printLine
echo "-- Container logs ($POD_NAME) --"
minikube kubectl -- logs "$POD_NAME" -c lighty-intent-app-helm || true
minikube kubectl -- logs "$POD_NAME" -c lighty-intent-app-helm-slm || true

printLine
echo "-- RESTCONF reachable through the Service (NodePort) --"
assertHttpStatusCode "$(curl -o /dev/null -s -w '%{http_code}' \
  "http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/data/lighty-intent:intents")" \
  "GET http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/data/lighty-intent:intents"
sleep 1

printLine
echo "-- lighty-intent-slm sidecar reachable from the app container at localhost:8000 --"
SLM_HEALTH_STATUS=$(minikube kubectl -- exec "$POD_NAME" -c lighty-intent-app-helm -- \
  wget -q -O /dev/null -S "http://localhost:8000/health" 2>&1 | awk '/HTTP\//{print $2}' | tail -1)
assertHttpStatusCode "$SLM_HEALTH_STATUS" "GET (from app container) http://localhost:8000/health"
sleep 1

printLine
echo "-- Submit a transport QoS intent --"
INTENT_ID="ci-smoke-intent-1"
assertHttpStatusCode "$(curl -X POST -o /dev/null -s -w '%{http_code}' \
  "http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/data/lighty-intent:intents" \
  -H "Content-Type: application/yang-data+json" -d '{
  "lighty-intent:intent": [{
    "intent-id": "'"$INTENT_ID"'", "intent-name": "CI smoke test intent",
    "domain": "lighty-intent-transport:transport", "source": "API",
    "expectation": [{
      "expectation-id": "exp-1",
      "expectation-target": [{
        "expectation-object-type": "lighty-intent-transport:min-bandwidth",
        "expectation-object-instance": "eth0",
        "target-condition": "IS_GREATER_THAN", "target-value-range": ["100"], "unit": "Mbps"
      }]
    }]
  }]
}')" "POST http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/data/lighty-intent:intents"
sleep 2

printLine
echo "-- Check the intent reached AWAITING_APPROVAL --"
LIFECYCLE_STATE="UNKNOWN"
for i in {1..10}; do
  LIFECYCLE_STATE=$(curl -s \
    "http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/data/lighty-intent:intents/intent=$INTENT_ID?content=all" \
    | grep -o '"lifecycle-state"[[:space:]]*:[[:space:]]*"[A-Z_]*"' | grep -o '"[A-Z_]*"$' | tr -d '"')
  echo "Lifecycle state check $i: $LIFECYCLE_STATE"
  if [[ "$LIFECYCLE_STATE" == "AWAITING_APPROVAL" ]]; then
    echo "Test passed"
    break
  fi
  sleep 2
done
if [[ "$LIFECYCLE_STATE" == "AWAITING_APPROVAL" ]]; then
  test_results+=(0)
else
  test_results+=(1)
fi

printLine
echo "-- dry-run the intent --"
assertHttpStatusCode "$(curl -X POST -o /dev/null -s -w '%{http_code}' \
  "http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/operations/lighty-intent:dry-run" \
  -H "Content-Type: application/yang-data+json" \
  -d '{"input": {"intent-id": "'"$INTENT_ID"'"}}')" \
  "POST http://$MINIKUBE_IP:$RESTCONF_NODE_PORT/rests/operations/lighty-intent:dry-run"

# Check if any test failed
if [[ "${test_results[@]}" =~ 1 ]]; then
  exit 1
fi
