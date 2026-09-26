# lighty.io intent-driven-management (IDM) application

Runnable lighty.io application exposing intent-driven management over RESTCONF: submit an
intent, dry-run it, approve it, and it gets compiled and actuated onto a real device. See
[docs/intent-driven-management.md](../../docs/intent-driven-management.md) for the full
architecture, lifecycle, and standards mapping; this README only covers running *this*
application.

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
- [lighty-intent-ran](../../lighty-modules/lighty-intent/lighty-intent-ran)'s `RanCcoCompiler`,
  wired for the `lighty-intent-ran-cco:ran` domain — compiles, so `dry-run` works, but **no
  actuator is registered** for it in this version, so `approve-intent` on a RAN CCO intent fails
  cleanly (documented, intentional P2 scope; see that module's own README).
- [lighty-intent-nl-client](../../lighty-modules/lighty-intent/lighty-intent-nl-client)'s
  `FallbackNlTranslator`, chaining a `SidecarNlTranslator` (talking to the
  [lighty-intent-slm](../../lighty-intent-slm) sidecar) in front of a `RuleBasedNlTranslator`
  offline fallback.

Reduced scope versus [lighty-rnc-app](../lighty-rnc-app-aggregator): no AAA, no OpenApi, no
NETCONF southbound/call-home. No Helm chart is included in this version either — Docker/
docker-compose is the supported deployment path for now.

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
  "nl-slm": {
    "baseUrl": "http://localhost:8000"
  }
}
```

- `gnmi-target`: the single gNMI device `GnmiTransportActuator` actuates transport intents
  against. The gRPC channel connects lazily on the first `approve-intent` for a transport-domain
  intent, not at startup, so a temporarily-unreachable target does not stop the application from
  starting.
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

## Setup logging

Default logging configuration may be overridden with the JVM option
`-Dlog4j.configurationFile=path/to/log4j2.xml` (see the
[log4j2 manual](https://logging.apache.org/log4j/2.x/manual/configuration.html)), the same as
every other lighty.io application in this repository.
