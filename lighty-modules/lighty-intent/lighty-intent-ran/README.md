# lighty-intent-ran

RAN Coverage/Capacity-Optimization (CCO) intent domain for lighty.io's intent-driven
management (IDM) module, realizing O-RAN Alliance SMO Intent-Driven Management on top of
the 3GPP TS 28.312-style core intent model (see `lighty-intent-models`).

## What it does

* `RanCcoCompiler` implements `IntentCompiler` for the `lighty-intent-ran-cco:ran` domain and
  always produces a feasible `ran-cco-plan` (target cells + an illustrative A1 policy body), so
  `dry-run` on a RAN CCO intent previews the compiled plan.
* `RanCcoActuator` implements `DomainActuator` for the same domain: it parses the compiled plan's
  `a1-policy-type-id`/`a1-policy-json` back out and calls `A1PolicyClient.createPolicy`
  (`remove` calls `deletePolicy`, using the policy type it remembered from `apply`). Once an app
  aggregator registers it with lighty-intent-core (see `lighty-intent-app-aggregator`'s
  `IntentAppModule`), `approve-intent` on a RAN CCO intent actually creates/deletes an A1 policy
  on the configured Near-RT RIC.
* `A1PolicyClient` / `HttpA1PolicyClient` talk the O-RAN Alliance A1-P REST interface directly;
  both are unit-tested against a loopback HTTP server (`HttpA1PolicyClientTest`,
  `RanCcoActuatorTest`).
* `RanAssuranceSource` implements `AssuranceSource`, but it is backed purely by an in-memory map
  fed through its own `ingestVesEvent(String)` method; there is no deployed HTTP endpoint, and
  lighty-intent-core does not poll or register this source either — assurance/reporting for this
  domain is still deferred follow-up work (see below).

## Follow-up work (not in this version)

* A real A1 policy-type schema (registered with the Near-RT RIC's policy-type catalog) in place
  of `RanCcoCompiler`'s placeholder `a1-policy-type-id`
  (`org.o-ran-sc.ies.trafficsteering:1.0.0`, chosen only because it is plausible-looking).
* Real O1 PM/VES event parsing in `RanAssuranceSource`, in place of the minimal illustrative JSON
  shape `ingestVesEvent(String)` currently accepts
  (`{"cellId": ..., "prbUtilizationPercent": ...}`, optionally batched under `"measurements"`).
* A real ingest endpoint (HTTP or otherwise) feeding `RanAssuranceSource.ingestVesEvent`, and
  registering `RanAssuranceSource` with lighty-intent-core so fulfilment reports actually get
  published for RAN CCO intents.
* Conflict detection between RAN CCO intents targeting overlapping cells (`RanCcoCompiler.compile`
  currently accepts `activeIntents` but does not check against it).
* `RanCcoActuator`'s `policyTypeIdByIntentId` bookkeeping (needed so `remove(intentId)` knows
  which A1 policy type to delete under) is in-memory only, lost on restart — same known
  limitation as `RanAssuranceSource`'s own PRB map and lighty-intent-core's conflict detection
  elsewhere in this project. A restart-safe redeploy would need to re-derive it from the intent
  store instead.
