# lighty.io Intent-Driven Management (IDM)

This document describes the design and architecture of lighty.io's intent-driven
management (IDM) module: the YANG models, the Java SPI, the request/approval
pipeline, and the roadmap for turning operator (or natural-language) intents
into deployed, assured network configuration.

## 1. Overview

lighty.io's IDM module lets lighty act as an SMO (Service Management and
Orchestration) intent-handling function. An operator (through RESTCONF, or
indirectly through a natural-language request handled by the `lighty-intent-slm`
sidecar) submits an **intent**: a declarative statement of the network
behaviour they want, expressed as a set of expectations and expectation
targets rather than as device configuration. lighty-intent-core validates the
intent, checks it for conflicts against everything already deployed, compiles
it into a domain-specific configuration plan, and shows that plan back to the
caller through a `dry-run` RPC with **no side effect on the network**. Only an
explicit `approve-intent` call — a human (or an automated approver acting on a
human's behalf) accepting the plan — causes lighty to actuate anything.
Once deployed, lighty periodically compares observed network state against
the intent's expectation targets and publishes fulfilment reports, so an
intent's lifecycle state reflects whether it is actually being met, not just
whether it was accepted.

The rest of this document maps that pipeline onto the concrete YANG models
and Java interfaces already committed to this module, and lays out what is
implemented in this version versus planned for later ones.

## 2. Standards mapping

lighty's IDM module does not invent a new intent model. It implements two
existing standards as one lifecycle:

- **3GPP TS 28.312 "Intent driven management services for mobile networks"**
  supplies the core, technology-neutral shape: an `Intent` made of one or more
  `IntentExpectation`s, each expectation carrying `ExpectationTarget`s (the KPI
  or object being asked for) and `ExpectationContext` (the conditions under
  which the expectation applies), plus an `IntentReport` for fulfilment
  assurance. `lighty-intent.yang` implements this core almost directly:
  `intents/intent/expectation/expectation-target` and
  `.../expectation-context` are the `expectation-target` and
  `context-parameter` groupings, `expectation-verb` mirrors TS 28.312's
  `expectationVerb` (`DELIVER`/`ENSURE`/`AVOID`/`MAINTAIN`/`REPORT`), and
  `comparison-condition` mirrors its target/context condition comparators
  (`IS_EQUAL_TO`, `IS_GREATER_THAN`, `IS_WITHIN_RANGE`, ...). `intent-reports`
  and its `fulfilment-status` enum (`FULFILLED`/`DEGRADED`/`NOT_FULFILLED`/
  `UNKNOWN`) are the `IntentReport` side of the same model.
- **O-RAN Alliance SMO Intent-Driven Management** is treated as the RAN-domain
  realization of that same TS 28.312 lifecycle, not a competing model. Its
  concepts (coverage/capacity intents actuated as A1 policies to the Near-RT
  RIC, assured via O1 PM) are exactly what `lighty-intent-ran-cco.yang`'s
  `ran` domain, its expectation-object-type identities, and its
  `ran-cco-plan` container are shaped to carry, once `RanCcoCompiler` is
  implemented (see roadmap, P2).

**Forward-compatibility note.** TS 28.312 and O-RAN's IDM framework are
treated here as today's baseline, not a final shape: 3GPP's intent-management
work is expected to keep evolving into the 6G era, and that evolution is
expected to *extend* the Intent/Expectation/Target/Context model rather than
replace it. This is exactly why `lighty-intent.yang` puts the two points where
a new domain or a future 6G expectation object would need to hook in —
`expectation-object-type` and `intent-domain` — behind open YANG `identity`
extension points instead of closed enumerations. A new domain module (or a
future revision reacting to 6G intent work) adds `identity` statements
deriving from these bases; it never has to change `lighty-intent.yang` itself,
and existing domain modules and compilers are unaffected.

## 3. Architecture

### 3.1 Pipeline

```
 operator (NL text)
        |
        v
 lighty-intent-slm sidecar  --  POST /v1/translate
        |                       (rule-based extractor in this version;
        |                        JSON-schema-constrained SLM generation
        |                        is the target production approach, see §6)
        v
 draft intent JSON  (RFC 7951, "lighty-intent:intent" list entry)
        |
        v
 RESTCONF POST /rests/data/lighty-intent:intents/intent
        |
        v
 +----------------------------- lighty-intent-core --------------------------+
 |                                                                           |
 |   validate (schema + mandatory fields)                                   |
 |        |                                                                 |
 |        v                                                                 |
 |   conflict detection                                                     |
 |     - ConflictRule(s): cross-domain checks                               |
 |     - IntentCompiler#compile(): domain-specific checks                   |
 |        |                                                                 |
 |        v                                                                 |
 |   IntentCompiler (looked up by the intent's `domain` identityref)        |
 |        |                                                                 |
 |        v                                                                 |
 |   CompiledPlan (feasible + renderedConfigJson, or infeasible + conflicts)|
 |        |                                                                 |
 |        v                                                                 |
 |   RPC dry-run  --  shows the plan/diff, NO network side effect           |
 |        |                                                                 |
 |        v                                                                 |
 |   lifecycle-state = AWAITING_APPROVAL                                    |
 |        |                                                                 |
 |        v                                                                 |
 |   RPC approve-intent (human approval)                                    |
 |        |                                                                 |
 |        v                                                                 |
 |   DomainActuator#apply()  --  actuates the SAME CompiledPlan             |
 |        |                       over NETCONF (lighty-netconf-sb) or       |
 |        |                       gNMI (lighty-gnmi-sb)                     |
 |        v                                                                 |
 |   lifecycle-state = DEPLOYING -> ACTIVE                                  |
 |        |                                                                 |
 |        v                                                                 |
 |   AssuranceSource#observe()  --  polls expectation targets               |
 |        |                                                                 |
 |        v                                                                 |
 |   intent-reports/intent-report (fulfilment-status)                       |
 |        |                                                                 |
 |        v                                                                 |
 |   notification intent-report-published / intent-lifecycle-changed        |
 |                                                                           |
 +---------------------------------------------------------------------------+
```

The split between `IntentCompiler` (pure, side-effect-free) and
`DomainActuator` (the only component allowed to touch the network, and only
after `approve-intent`) is what makes `dry-run` safe to call repeatedly: it
runs exactly the same compilation step actuation will later use, without ever
invoking a `DomainActuator`.

### 3.2 Northbound surfaces

Two northbound surfaces are in scope for this module:

1. **RESTCONF over the `lighty-intent*` YANG models** is the source of truth
   in this version. All CRUD on intents, the `translate-nl`/`dry-run`/
   `approve-intent`/`reject-intent` RPCs, and the `intent-lifecycle-changed`/
   `intent-report-published` notifications are exposed exactly as modelled in
   `lighty-intent.yang`, through lighty's existing RESTCONF northbound plugin.
2. **A 3GPP TS 28.312-OpenAPI-shaped REST facade** (planned artifact:
   `lighty-intent-openapi-facade`), translating the same operations into the
   REST/OpenAPI surface TS 28.312 and SMO/OSS tooling expect, for interop with
   non-RESTCONF SMO/OSS clients. **This facade is not implemented in this
   version** — no such module exists under `lighty-modules/lighty-intent` yet
   — and is tracked as roadmap item P3.

## 4. Lifecycle state machine

`intent-lifecycle-state` (`lighty-intent.yang`) is the authoritative state for
an intent, mirrored by lighty-intent-core into `intents/intent/lifecycle-state`
(OPERATIONAL, `config false`) and reported on every transition via the
`intent-lifecycle-changed` notification.

| State | Entered when | Notes |
|---|---|---|
| `DRAFT` | An intent is written to `/intents/intent` (directly, or via a `translate-nl`-drafted JSON the caller POSTs) | Default state; not yet validated |
| `VALIDATED` | `dry-run` (or an equivalent internal validation pass) finds no schema/conflict problems | Precedes compilation succeeding |
| `AWAITING_APPROVAL` | `dry-run` produces a *feasible* `CompiledPlan` | The plan is cached in `active-plan`; waits for a human |
| `FAILED` | Validation, conflict detection, or a later deployment step fails; also entered directly via `reject-intent` | `lifecycle-detail` carries the reason |
| `DEPLOYING` | `approve-intent` succeeds | `DomainActuator#apply()` is invoked with the cached `CompiledPlan` |
| `ACTIVE` | Actuation completes and, where an `AssuranceSource` exists, targets are currently met | Steady state for a healthy intent |
| `DEGRADED` | An `AssuranceSource` reports partial fulfilment for an `ACTIVE` intent | Reported via `intent-report-published`; no automatic remediation (see roadmap P5) |
| `SUSPENDED` | An operator withdraws a deployed intent without deleting it | `DomainActuator#remove()` is called; can presumably be re-approved later |
| `DELETED` | An operator deletes the intent | `DomainActuator#remove()` is called; the entry is retired |

```
DRAFT --validate--> VALIDATED --dry-run(feasible)--> AWAITING_APPROVAL
  |                     |                                   |
  |                 (conflict/invalid)                 approve-intent
  |                     v                                   v
  +------------------> FAILED <----(deploy failure)---- DEPLOYING --> ACTIVE
                                                                          |
                                                              assurance degradation
                                                                          v
                                                                      DEGRADED
                                          (any deployed state) --suspend--> SUSPENDED
                                          (any deployed state) --delete--> DELETED
```

## 5. Conflict model

Conflict detection runs in two places, both driving the `conflict`/`conflicts`
leaf-lists returned by `dry-run` and stored in `active-plan`:

1. **Cross-domain rules — `ConflictRule`.** Each registered `ConflictRule`
   receives the candidate intent and the collection of other intents
   currently `ACTIVE`, `AWAITING_APPROVAL`, or `DEPLOYING`, and returns a
   human-readable description for each conflict it finds. Because a single
   `ConflictRule` can inspect intents from *any* domain, this is where
   conflicts that are not specific to one domain's compiled configuration
   belong — the Javadoc's own example is a RAN coverage intent raising cell
   power in an area an energy-saving intent is simultaneously dimming.
2. **Domain-specific checks — `IntentCompiler#compile()`.** Each
   `IntentCompiler` additionally checks the candidate intent against the same
   `activeIntents` collection for conflicts specific to its own domain's
   compiled configuration (e.g. two transport intents both claiming exclusive
   bandwidth on the same interface). A compiler that finds such a conflict
   returns `CompiledPlan.infeasible(domain, conflicts)` instead of a feasible
   plan.

Both checks run identically in two places: during `dry-run` (so the operator
sees every conflict before approving) and again on the same `CompiledPlan`
computation path right before actuation after `approve-intent` — since no
compiler or conflict rule has a side effect, re-running them immediately
before `DomainActuator#apply()` is cheap and closes the window between
approval and deployment during which some *other* intent could have been
approved and deployed first.

## 6. SLM safety model

The `lighty-intent-slm` sidecar is the entry point for natural-language
intent authoring, backing the `translate-nl` RPC through the `NlTranslator`
SPI. Its safety model rests on one boundary, enforced structurally rather
than by trusting the model:

- **The sidecar only drafts.** `NlTranslator` implementations "never write to
  the intent store and never actuate anything" — a translator only produces
  a draft (`TranslationResult`: `INTENT_DRAFTED`, `CLARIFICATION_NEEDED`, or
  `ERROR`) that the caller still has to review and POST to
  `/intents/intent` themselves, unmodified or edited. The sidecar process has
  no network credentials and no path to `DomainActuator`.
- **The output is schema-constrained.** `lighty-intent-slm/schema/intent.schema.json`
  defines the exact shape a draft's `draft-intent-json` must have: a single
  `lighty-intent:intent` entry whose `domain` and every
  `expectation-target/expectation-object-type` are restricted to the
  identities this repository's YANG modules actually define (e.g.
  `lighty-intent-transport:transport`, `lighty-intent-transport:min-bandwidth`,
  `lighty-intent-ran-cco:prb-utilization-max`), with `additionalProperties:
  false` throughout. The intended production approach is constrained/
  schema-based generation: a small open-weight model (in the 1-4B parameter
  range) decoding under this JSON Schema, with few-shot examples and
  inventory/topology RAG for grounding. **This version ships a rule-based
  extractor behind the same HTTP contract** (the sidecar's `/v1/translate`
  endpoint and the `intent.schema.json` output shape), so the rest of the
  pipeline — and the safety argument below — does not depend on which
  implementation sits behind that contract.
- **Every drafted intent still goes through the full pipeline.** A
  `translate-nl` output is not a shortcut: once submitted, it is validated,
  conflict-checked, dry-run, and only actuated after an explicit
  `approve-intent`, exactly like an intent submitted directly as
  `source: API` JSON. The actual safety boundary is the human
  `approve-intent` gate in §3-4, not any property of the translation model —
  a wrong or nonsensical draft is expected to be caught by validation,
  conflict detection, or the operator reading the `dry-run` diff, not by the
  model getting it right.

## 7. YANG model overview

### 7.1 `lighty-intent` (`urn:lighty:intent`)

The technology-neutral core (see §2). Key containers/identities:

- `identity intent-domain`, `identity expectation-object-type` — open
  extension points domain modules derive from.
- `typedef intent-lifecycle-state`, `typedef fulfilment-status`,
  `typedef expectation-verb`, `typedef comparison-condition` — see §4 and §2.
- `grouping expectation` / `expectation-target` / `context-parameter` — the
  TS 28.312 `IntentExpectation`/`ExpectationTarget`/`ExpectationContext`
  shapes.
- `container intents { list intent { ... } }` — the intent store. Clients
  write intent content to CONFIG; lighty-intent-core mirrors the full entry
  (including `lifecycle-state`, `lifecycle-detail`, `created-timestamp`,
  `approved-by`, and the `active-plan` snapshot with `rendered-config-json`,
  `conflicts`, `feasible`) to OPERATIONAL.
- `container intent-reports { list intent-report { ... } }` — one
  `IntentReport` per intent per observation window, keyed by
  `intent-id report-timestamp`, with per-target `target-fulfilment` entries.
- RPCs: `translate-nl`, `dry-run`, `approve-intent`, `reject-intent`.
- Notifications: `intent-lifecycle-changed`, `intent-report-published`.

### 7.2 `lighty-intent-transport` (`urn:lighty:intent:transport`)

Transport (QoS/ACL/isolation) domain. Adds:

- `identity transport` (base `intent-domain`).
- `identity min-bandwidth`, `max-latency`, `max-packet-loss`,
  `traffic-isolation` (base `expectation-object-type`).
- `container transport-qos-config` — the compiled, device-facing
  configuration `TransportQosCompiler` renders and `DomainActuator`
  actuates over NETCONF (`lighty-netconf-sb`) or gNMI (`lighty-gnmi-sb`):
  `list qos-policy` (`qos-class`, `min-bandwidth-mbps`, `max-latency-ms`,
  `max-packet-loss-percent`, `applied-interface`), `list acl-policy`
  (`acl-name`, keyed `acl-entry` list with `match-source-network` /
  `match-destination-network` / `action`), and `list network-instance`
  (`name`, `interface`) for VRF-based isolation.

This container is deliberately a **lighty-owned subset** shaped after
OpenConfig's `openconfig-qos`, `openconfig-acl`, and
`openconfig-network-instance`, rather than a vendored copy of those modules.
Vendoring the real OpenConfig QoS/ACL/network-instance tree pulls in a large
transitive dependency graph (`openconfig-policy-types`,
`openconfig-routing-policy`, `openconfig-mpls-types`, `openconfig-if-ip`, and
others) that lighty does not otherwise carry in this version — replacing this
subset with the real, vendored OpenConfig models is roadmap item P1.5 (§11).

### 7.3 `lighty-intent-ran-cco` (`urn:lighty:intent:ran-cco`)

RAN Coverage/Capacity Optimization domain — the O-RAN SMO IDM realization
(§2). Adds:

- `identity ran` (base `intent-domain`).
- `identity prb-utilization-max`, `cell-coverage-target`,
  `cell-edge-throughput-min` (base `expectation-object-type`).
- `container ran-cco-plan` (`config false`) — a **skeleton** of the compiled
  A1 policy plan: `target-cell` (leaf-list), `a1-policy-type-id`, and an
  opaque `a1-policy-json` whose schema depends on `a1-policy-type-id`.

As the module's own description states, this is a P2 skeleton: it defines
the expectation-object-type identities and the shape of a compiled A1 policy
plan, but `RanCcoCompiler`, the A1 client, and O1 PM/VES assurance ingest are
**not wired into lighty-intent-core's deployment path in this revision** (see
roadmap P2, §11).

## 8. API examples

All examples assume RESTCONF is exposed on the usual lighty RESTCONF
northbound plugin base path, `/rests`.

### 8.1 Submit a transport QoS + isolation intent

```bash
curl -u admin:admin -X POST \
  http://localhost:8888/rests/data/lighty-intent:intents/intent \
  -H "Content-Type: application/yang-data+json" \
  -d '{
  "lighty-intent:intent": [
    {
      "intent-id": "intent-transport-001",
      "intent-name": "Guarantee URLLC slice QoS on edge link",
      "domain": "lighty-intent-transport:transport",
      "source": "API",
      "expectation": [
        {
          "expectation-id": "exp-1",
          "expectation-verb": "ENSURE",
          "expectation-priority": 2,
          "expectation-target": [
            {
              "expectation-object-type": "lighty-intent-transport:min-bandwidth",
              "expectation-object-instance": "if-ge0/0/1",
              "target-condition": "IS_GREATER_THAN",
              "target-value-range": ["500"],
              "unit": "Mbps"
            },
            {
              "expectation-object-type": "lighty-intent-transport:max-latency",
              "expectation-object-instance": "if-ge0/0/1",
              "target-condition": "IS_LESS_THAN",
              "target-value-range": ["5"],
              "unit": "ms"
            },
            {
              "expectation-object-type": "lighty-intent-transport:traffic-isolation",
              "expectation-object-instance": "if-ge0/0/1",
              "target-condition": "IS_EQUAL_TO",
              "target-value-range": ["true"]
            }
          ],
          "expectation-context": [
            {
              "context-attribute": "site",
              "context-condition": "IS_EQUAL_TO",
              "context-value-range": ["edge-site-12"]
            }
          ]
        }
      ]
    }
  ]
}'
```

### 8.2 Dry-run the intent

```bash
curl -u admin:admin -X POST \
  http://localhost:8888/rests/operations/lighty-intent:dry-run \
  -H "Content-Type: application/yang-data+json" \
  -d '{
  "input": {
    "intent-id": "intent-transport-001"
  }
}'
```

A feasible response echoes back the compiled configuration as opaque JSON
(the `TransportQosCompiler`-rendered `lighty-intent-transport:transport-qos-config`)
and moves the intent to `AWAITING_APPROVAL`:

```json
{
  "output": {
    "feasible": true,
    "domain-compiler": "lighty-intent-transport:transport",
    "rendered-config-json": "{\"lighty-intent-transport:transport-qos-config\":{\"qos-policy\":[{\"qos-class\":\"urllc\",\"min-bandwidth-mbps\":500,\"max-latency-ms\":5,\"applied-interface\":[\"ge0/0/1\"]}],\"network-instance\":[{\"name\":\"vrf-intent-transport-001\",\"interface\":[\"ge0/0/1\"]}]}}",
    "conflict": []
  }
}
```

### 8.3 Approve the intent (triggers actuation)

```bash
curl -u admin:admin -X POST \
  http://localhost:8888/rests/operations/lighty-intent:approve-intent \
  -H "Content-Type: application/yang-data+json" \
  -d '{
  "input": {
    "intent-id": "intent-transport-001",
    "approved-by": "netops-alice"
  }
}'
```

```json
{ "output": { "lifecycle-state": "DEPLOYING" } }
```

### 8.4 Translate a natural-language request

```bash
curl -u admin:admin -X POST \
  http://localhost:8888/rests/operations/lighty-intent:translate-nl \
  -H "Content-Type: application/yang-data+json" \
  -d '{
  "input": {
    "nl-text": "Guarantee at least 500 Mbps and under 5ms latency, isolated from other traffic, on the edge link at site edge-site-12",
    "context-hint": "transport"
  }
}'
```

```json
{
  "output": {
    "status": "INTENT_DRAFTED",
    "draft-intent-json": "{\"lighty-intent:intent\":[{\"intent-id\":\"intent-nl-042\",\"intent-name\":\"NL request: edge link QoS\",\"domain\":\"lighty-intent-transport:transport\",\"source\":\"NL_SLM\",\"original-nl-text\":\"Guarantee at least 500 Mbps ...\",\"expectation\":[...]}]}",
    "confidence": 0.81
  }
}
```

The caller reviews `draft-intent-json` and POSTs it (edited or as-is) to
`/rests/data/lighty-intent:intents/intent`, exactly as in §8.1 — `translate-nl`
never writes it there itself.

## 9. Deployment

The target shape is a `lighty-intent-app` container (the RESTCONF-exposed
lighty application hosting `lighty-intent-core`, its registered
`IntentCompiler`/`DomainActuator`/`ConflictRule`/`AssuranceSource`
implementations, and the RESTCONF/NETCONF/gNMI northbound and southbound
plugins) alongside a `lighty-intent-slm` sidecar container on the same Docker
network, with the sidecar's URL passed to the app as ordinary lighty JSON
configuration (mirroring how `RncLightyModuleConfiguration` carries
per-plugin config in the RNC application):

```yaml
version: "3.8"

services:
  lighty-intent-app:
    image: lighty-intent-app:23.0.0-SNAPSHOT
    container_name: lighty-intent-app
    ports:
      - "8888:8888"     # RESTCONF
    environment:
      - LIGHTY_INTENT_SLM_URL=http://lighty-intent-slm:9000
    volumes:
      - ./configuration.json:/lighty-intent/configuration.json
    depends_on:
      - lighty-intent-slm
    networks:
      - lighty-intent-net

  lighty-intent-slm:
    image: lighty-intent-slm:23.0.0-SNAPSHOT
    container_name: lighty-intent-slm
    expose:
      - "9000"          # POST /v1/translate
    networks:
      - lighty-intent-net

networks:
  lighty-intent-net:
    driver: bridge
```

This mirrors the existing `lighty-rnc-app-docker`/`lighty-rnc-app-helm` split
in `lighty-rnc-app-aggregator`: a `lighty-intent-module` (the
`AbstractLightyModule` wiring, analogous to `RncLightyModule`), a
`lighty-intent-app` (the runnable distribution, analogous to `lighty-rnc-app`),
and `lighty-intent-app-docker`/`lighty-intent-app-helm` siblings for container
and Kubernetes packaging — none of which exist on disk yet at time of writing,
but which the `lighty-intent-app-aggregator` is expected to mirror
module-for-module once created.

## 10. Test lab

Two southbound test doubles are in scope, matched to where each domain
actually actuates in this version:

- **Transport domain — lighty's in-JVM gNMI device simulator.**
  `lighty-modules/lighty-gnmi/lighty-gnmi-device-simulator` already provides
  an in-JVM gNMI target lighty's own integration tests run against; the
  transport domain's test lab loads it with the `transport-qos-config` model
  from `lighty-intent-transport.yang` so `TransportQosCompiler`'s rendered
  `qos-policy`/`acl-policy`/`network-instance` entries can be pushed over gNMI
  `Set` and read back over gNMI `Get`/`Subscribe` without any external
  process — the same pattern `lighty-gnmi-device-simulator` already supports
  for other gNMI-facing lighty modules.
- **RAN CCO domain — O-RAN SC O1 NETCONF simulators (future, P2).** Once
  `RanCcoCompiler` and the O1 PM/VES assurance ingest are implemented, the RAN
  CCO test lab is expected to use the O-RAN Software Community's O1 NETCONF
  simulators (the SMO's usual O1 test double) as the assured device side,
  alongside a mocked or sandboxed A1 endpoint for policy actuation to the
  Near-RT RIC. Nothing in this domain is wired to actuation yet, so no such
  lab exists in this version — this is a target, not a description of an
  existing test resource.

## 11. Roadmap

- **P1 (this version)** — the vertical slice this document describes end to
  end for the transport domain: NL text -> `translate-nl` draft -> intent
  submission -> `dry-run` -> `approve-intent` -> gNMI-deployed transport QoS
  (`transport-qos-config`), with the rule-based NL extractor behind the
  `lighty-intent-slm` HTTP contract.
  - **P1.5** — replace the `transport-qos-config` subset with the real,
    vendored OpenConfig `openconfig-qos`, `openconfig-acl`, and
    `openconfig-network-instance` modules once that dependency graph
    (`openconfig-policy-types`, `openconfig-routing-policy`,
    `openconfig-mpls-types`, `openconfig-if-ip`, etc.) is brought into lighty.
- **P2** — RAN CCO wired to real actuation: implement `RanCcoCompiler`, an A1
  client to the Near-RT RIC, and O1 PM/VES ingest as a RAN `AssuranceSource`,
  against the O-RAN SC O1 simulators described in §10.
- **P3** — the full `IntentReport` assurance loop (periodic, not just
  best-effort, observation across domains) and the 3GPP TS 28.312 OpenAPI
  facade (`lighty-intent-openapi-facade`) for SMO/OSS interop (§3.2).
- **P4** — replace the rule-based NL extractor with a fine-tuned, JSON-schema-
  constrained small language model behind the same `lighty-intent-slm`
  `/v1/translate` contract (§6), without any change to the pipeline downstream
  of `translate-nl`.
- **P5** — closed-loop automatic remediation: letting a `DEGRADED` intent's
  fulfilment reports trigger automatic re-planning/re-deployment, rather than
  only a notification, as noted as explicitly out of scope for
  `AssuranceSource` in this version (§2, `lighty-intent-api`).
