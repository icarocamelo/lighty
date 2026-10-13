# lighty.io intent-driven-management (IDM) application

Runnable lighty.io application exposing intent-driven management over RESTCONF: submit an
intent, dry-run it, approve it, and it gets compiled and actuated onto a real device. See
[docs/intent-driven-management.md](../../docs/intent-driven-management.md) for the full
architecture, lifecycle, and standards mapping; this README only covers running *this*
application.

## What problems does this solve?

- **Operators shouldn't have to hand-write device config to express an outcome.** "Guarantee
  100Mbps on this link" or "keep this cell's PRB utilization under 70%" are the actual asks;
  OpenConfig QoS policies and A1 policy JSON are implementation detail. `translate-nl` lets an
  operator submit plain language (or a standards-shaped intent directly) and have it compiled to
  the right device-specific config — this fits here because lighty.io already owns the YANG/schema
  knowledge of what each southbound target accepts; Kubernetes has no notion of an OpenConfig QoS
  schema or an A1 policy type to translate into, so there's no shortcut to this logic on that side.
- **Multiple actuation domains need protocol-aware mediation, not just a declarative diff.** A
  transport intent becomes OpenConfig pushed over gNMI (`GnmiTransportActuator`); a RAN CCO intent
  becomes an A1 policy PUT over A1-P REST (`RanCcoActuator`). This module reuses lighty's existing
  gNMI southbound stack and adds a purpose-built A1 REST client sitting right next to it. A
  Kubernetes controller doing the same job would still need a gNMI client and an A1 client — i.e.
  it would have to re-embed this exact mediation code, just in a different runtime, not replace it
  with something Kubernetes-native.
- **Automated network changes need a safety gate before anything is pushed.** `dry-run`, explicit
  human `approve-intent`, and conflict detection against already-active intents all have to happen
  *before* a device sees any config. MD-SAL's transactional CONFIG/OPERATIONAL datastore gives a
  natural home for "compute a plan, show it, commit only on approval" — `IntentLightyModule`'s
  lifecycle state machine (`DRAFT` → `VALIDATED` → `AWAITING_APPROVAL` → `DEPLOYING` →
  `ACTIVE`/`FAILED`) is built directly on it. Kubernetes' reconcile loops are eventually-consistent
  and have no built-in notion of "hold a candidate plan pending human sign-off"; that machinery
  would have to be built from scratch on top of a CRD/controller instead of reused.
- **A private API doesn't interoperate with the rest of an operator's OSS/SMO stack.** The
  3GPP TS 28.312-shaped `lighty-intent.yang` model exposed over RESTCONF speaks the same NBI
  technology every other lighty.io application in this repository already exposes, with a 3GPP
  28.312 OpenAPI facade as a natural next step (roadmap P3 in the design doc). A Kubernetes CRD is
  a schema only `kubectl`/controller-runtime understands — an SMO/OSS system would need a
  translation layer in front of it, which is, again, this module.
- **Fulfilment needs to be reported back, not just "config was applied."** `IntentReport`/
  `intent-report-published` and `fulfilment-status` (`FULFILLED`/`DEGRADED`/...) close the loop via
  each domain's `AssuranceSource`. That assurance state belongs in the same datastore that already
  holds the intent and its target definition, not as a separate observability pipeline bolted onto
  a CRD's status subresource.

See "[Why lighty.io, not a Kubernetes operator?](#why-lightyio-not-a-kubernetes-operator)" below
for the broader architectural case.

Components wired together by `IntentAppModule`
([lighty-intent-module](lighty-intent-module)):

- [lighty.io controller](../../lighty-core/lighty-controller) — core MD-SAL/yangtools services.
- [RESTCONF Northbound plugin](../../lighty-modules/lighty-restconf-nb-community) — the only
  northbound interface this application exposes.
- [lighty-intent-core](../../lighty-modules/lighty-intent/lighty-intent-core) — the intent
  lifecycle/validation/conflict-detection engine and the `translate-nl`/`dry-run`/
  `approve-intent`/`reject-intent` RPCs.
- [lighty-intent-transport](../../lighty-modules/lighty-intent/lighty-intent-transport)'s
  `TransportQosCompiler` + `GnmiTransportActuator`, wired for the
  `lighty-intent-transport:transport` domain, actuating over gNMI against **one** configured
  target device.
- [lighty-intent-ran](../../lighty-modules/lighty-intent/lighty-intent-ran)'s `RanCcoCompiler` +
  `RanCcoActuator`, wired for the `lighty-intent-ran-cco:ran` domain — `approve-intent` on a RAN
  CCO intent creates/deletes a real A1 policy on the configured Near-RT RIC via
  `HttpA1PolicyClient` (O1 PM/VES-based assurance is still deferred follow-up work; see that
  module's own README).
- [lighty-intent-nl-client](../../lighty-modules/lighty-intent/lighty-intent-nl-client)'s
  `FallbackNlTranslator`, chaining a `SidecarNlTranslator` (talking to the
  [lighty-intent-slm](../../lighty-intent-slm) sidecar) in front of a `RuleBasedNlTranslator`
  offline fallback.

Reduced scope versus [lighty-rnc-app](../lighty-rnc-app-aggregator): no AAA, no OpenApi, no
NETCONF southbound/call-home, no pekko clustering (`IntentAppModule` only ever builds a
single-node controller, so the [Helm chart](lighty-intent-app-helm) is correspondingly leaner
than [lighty-rnc-app-helm](../lighty-rnc-app-aggregator/lighty-rnc-app-helm): no cluster-discovery
RBAC, no AAA/JMX, no HTTPS keystore Secret).

## Configuration

`IntentAppConfiguration` ([lighty-intent-module/.../config](lighty-intent-module/src/main/java/io/lighty/applications/intent/module/config))
is loaded from a lighty.io JSON config file (`-c path/to/configuration.json`) or from defaults
when no `-c` is given. Besides the standard `controller`/`restconf`/`lighty-server`/`modules`
sections (see [lighty-controller](../../lighty-core/lighty-controller)), it adds two
application-specific top-level sections:

```json
{
  "gnmi-target": {
    "host": "127.0.0.1",
    "port": 10161,
    "usePlainText": true,
    "username": null,
    "password": null
  },
  "a1-target": {
    "baseUrl": "http://127.0.0.1:9990"
  },
  "nl-slm": {
    "baseUrl": "http://localhost:8000"
  }
}
```

- `gnmi-target`: the single gNMI device `GnmiTransportActuator` actuates transport intents
  against. The gRPC channel connects lazily on the first `approve-intent` for a transport-domain
  intent, not at startup, so a temporarily-unreachable target does not stop the application from
  starting.
- `a1-target.baseUrl`: base URL of the Near-RT RIC `RanCcoActuator` actuates RAN CCO intents
  against, via `HttpA1PolicyClient`'s A1-P REST calls
  (`PUT`/`DELETE {baseUrl}/A1-P/v2/policytypes/{policyTypeId}/policies/{intentId}`). Like
  `gnmi-target`, this is only contacted on `approve-intent`/`reject-intent`, not at startup.
- `nl-slm.baseUrl`: base URL of the `lighty-intent-slm` sidecar backing `translate-nl`. If it is
  unreachable, `translate-nl` transparently falls back to the offline rule-based translator (see
  `lighty-intent-nl-client`'s README) — it does not fail the RPC.

The default configuration (`IntentAppConfigUtils.loadDefaultConfig()`) always merges in the
`lighty-intent`/`lighty-intent-transport`/`lighty-intent-ran-cco` YANG models on top of whatever
`schemaServiceConfig.topLevelModels` a config file supplies, so a config file only needs to list
the standard controller/RESTCONF models (see
[example-config/configuration.json](lighty-intent-app-docker/example-config/configuration.json)).

## Build and run locally

Prerequisites: Java 21+, Maven 3.9+, (optional) Docker.

```bash
mvn clean install -pl :lighty-intent-app -am
cd lighty-intent-app/target
unzip lighty-intent-app-<version>-bin.zip
java -jar lighty-intent-app-<version>.jar                       # defaults
java -jar lighty-intent-app-<version>.jar -c /path/to/config.json
```

A successful start logs `Intent lighty.io application started in ...`. The default RESTCONF
port is `8888`, base path `/rests`.

## Build and run with Docker

```bash
mvn clean install -P docker -pl lighty-applications/lighty-intent-app-aggregator/lighty-intent-app-docker -am
docker run -it --name lighty-intent --network host --rm lighty-intent
```

Or bring up the whole thing (this app + the `lighty-intent-slm` sidecar) with
[docker-compose.yaml](docker-compose.yaml):

```bash
docker compose -f lighty-applications/lighty-intent-app-aggregator/docker-compose.yaml up
```

This builds `lighty-intent-slm` from source and expects a `lighty-intent:latest` image already
built by the Maven command above (`docker-maven-plugin` tags the image `lighty-intent` per
[lighty-intent-app-docker/pom.xml](lighty-intent-app-docker/pom.xml)). Point
`example-config/configuration.json`'s `gnmi-target.host` at your real transport device, or at a
`lighty-gnmi-device-simulator`/testbed container you add to the compose file, before use.

## Try it: one intent lifecycle over RESTCONF

Using the default configuration (RESTCONF on `http://localhost:8888/rests`) and a gNMI target
reachable at the configured `gnmi-target` address:

```bash
# 1. Submit a transport QoS intent
curl -X POST http://localhost:8888/rests/data/lighty-intent:intents \
  -H "Content-Type: application/yang-data+json" -d '{
  "lighty-intent:intent": [{
    "intent-id": "intent-1", "intent-name": "Guarantee bandwidth on eth0",
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
}'

# 2. Check it reached AWAITING_APPROVAL (content=all: lifecycle-state is operational-only)
curl "http://localhost:8888/rests/data/lighty-intent:intents/intent=intent-1?content=all"

# 3. Preview the compiled config (no side effect on the network)
curl -X POST http://localhost:8888/rests/operations/lighty-intent:dry-run \
  -H "Content-Type: application/yang-data+json" -d '{"input": {"intent-id": "intent-1"}}'

# 4. Approve it - this is what actually deploys the plan over gNMI
curl -X POST http://localhost:8888/rests/operations/lighty-intent:approve-intent \
  -H "Content-Type: application/yang-data+json" \
  -d '{"input": {"intent-id": "intent-1", "approved-by": "netops-alice"}}'
```

Every RPC's response wraps its output under the module-qualified key `lighty-intent:output`
(e.g. `{"lighty-intent:output": {"lifecycle-state": "ACTIVE"}}`), not a bare `output` key — see
[docs/intent-driven-management.md](../../docs/intent-driven-management.md) section 8 for more
worked examples, including `translate-nl`.

The RAN CCO domain's `approve-intent` path works the same way, but deploys an A1 policy to the
configured `a1-target` instead of writing gNMI config:

```bash
curl -X POST http://localhost:8888/rests/data/lighty-intent:intents \
  -H "Content-Type: application/yang-data+json" -d '{
  "lighty-intent:intent": [{
    "intent-id": "intent-ran-1", "intent-name": "Improve cell-edge throughput",
    "domain": "lighty-intent-ran-cco:ran", "source": "API",
    "expectation": [{
      "expectation-id": "exp-1",
      "expectation-target": [{
        "expectation-object-type": "lighty-intent-ran-cco:prb-utilization-max",
        "expectation-object-instance": "cell-42",
        "target-condition": "IS_LESS_THAN", "target-value-range": ["70"], "unit": "%"
      }]
    }]
  }]
}'

curl -X POST http://localhost:8888/rests/operations/lighty-intent:approve-intent \
  -H "Content-Type: application/yang-data+json" \
  -d '{"input": {"intent-id": "intent-ran-1", "approved-by": "netops-alice"}}'
```

## Deployment via Helm chart

### Prerequisites

* Kubernetes cluster 1.22+ (minikube / microk8s / ...)
* helm 3.x

### Deploy

The chart at [lighty-intent-app-helm/helm/lighty-intent-app-helm](lighty-intent-app-helm/helm/lighty-intent-app-helm)
runs the app and the `lighty-intent-slm` sidecar as two containers in **one pod** (the sidecar is
reachable by the app at `http://localhost:8000`, not a Kubernetes Service). Make sure the
`lighty-intent` and `lighty-intent-slm` images named in `values.yaml` (`image.*`/`slmImage.*`) are
built and accessible to the cluster, then from the chart's `helm/` directory:

```bash
helm lint lighty-intent-app-helm/
helm template test lighty-intent-app-helm/        # render and inspect the manifests first
helm install lighty-intent lighty-intent-app-helm/
```

Override `lighty.gnmiTarget.*` and `lighty.a1Target.baseUrl` (and `ingress`/`nodePort`) via a
values file or `--set`, the same way `example-config/configuration.json` is adapted for Docker.

**Validation status:** this chart has been validated with `helm lint` and `helm template`
(default values and an overrides file) against a locally built `helm` binary in this project's
development sandbox — rendered manifests were reviewed by hand and the embedded
`configuration.json` ConfigMap entry was checked for valid JSON. It has **not** been verified with
a live `helm install` against a running cluster; no cluster or container registry was available to
do so at the time this chart was added.

## Why lighty.io, not a Kubernetes operator?

Kubernetes already has intent-flavored tooling (Crossplane, KubeVela, custom operators reconciling
CRDs), so it's a fair question why this engine lives in a lighty.io application instead of as
another K8s controller. A few reasons specific to what this application actually does:

- **The things being actuated are not Kubernetes objects.** `approve-intent` on a transport intent
  writes OpenConfig over gNMI to a physical/virtual router; `approve-intent` on a RAN CCO intent
  PUTs an A1 policy to a Near-RT RIC over A1-P REST (see `GnmiTransportActuator`,
  `RanCcoActuator`). A K8s controller's reconcile loop is built to drive the cluster's own API
  objects (or, via a provider like Crossplane, a cloud API) toward a declared spec; it has no
  native notion of a gNMI session, an A1 policy type, or a `DataBroker` transaction, so a
  Kubernetes-native version of this engine would still need to embed something equivalent to
  `lighty-intent-transport`/`lighty-intent-ran` as controller code, just without the standards
  model underneath it.
- **The interoperability target is a telecom standard, not the Kubernetes API.** This engine
  implements 3GPP TS 28.312 / O-RAN SMO Intent-Driven Management, and its `lighty-intent.yang`
  model is deliberately exposed over RESTCONF (and designed to grow a 28.312 OpenAPI facade — see
  `docs/intent-driven-management.md` roadmap P3) because that is what an SMO/OSS/inventory system
  actually speaks to interoperate. A CRD schema is legible to `kubectl`, not to the rest of an
  operator's OSS stack.
- **lighty.io already is the substrate this needs.** Representing intents against real network
  topology/inventory, validating and conflict-checking them transactionally, and pushing config
  safely requires a schema-aware (YANG-bound) datastore with CONFIG/OPERATIONAL semantics and
  NETCONF/gNMI southbound mediation — which is exactly what the lighty.io controller
  (`lighty-core/lighty-controller`, `lighty-netconf-sb`, `lighty-gnmi-sb`) already provides and
  what every other module in this repository is built on. Re-deriving that inside a Kubernetes
  operator would mean rebuilding a YANG binding/codegen layer and a transactional datastore from
  scratch, in a different language ecosystem, instead of reusing it.
- **The lifecycle logic doesn't fit "reconcile to desired state."** Human approval gating,
  cross-intent conflict detection over shared RF/VLAN/QoS resources, and PM/VES-based fulfilment
  assurance (`IntentLightyModule`'s lifecycle state machine; see §2 of the design doc) are
  intent-domain business logic, not "make actual match spec." It can be built as a controller, but
  it's fighting the grain of controller-runtime's reconcile model rather than using a lifecycle
  engine purpose-built for it.

None of this rules Kubernetes out — it's just a different layer. The [Helm chart above](#deployment-via-helm-chart)
uses Kubernetes for exactly what it's good at: packaging, running, and scaling this application and
the stateless `lighty-intent-slm` sidecar, while the actual intent reconciliation against the
network stays inside lighty.io.

## Setup logging

Default logging configuration may be overridden with the JVM option
`-Dlog4j.configurationFile=path/to/log4j2.xml` (see the
[log4j2 manual](https://logging.apache.org/log4j/2.x/manual/configuration.html)), the same as
every other lighty.io application in this repository.
