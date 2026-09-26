# lighty-intent-ran

RAN Coverage/Capacity-Optimization (CCO) intent domain for lighty.io's intent-driven
management (IDM) module, realizing O-RAN Alliance SMO Intent-Driven Management on top of
the 3GPP TS 28.312-style core intent model (see `lighty-intent-models`).

## This is a P2/P3 skeleton

This module gives the RAN CCO domain a real, tested shape, but it is **not wired into
lighty-intent-core's deployment path** in this version:

* `RanCcoCompiler` implements `IntentCompiler` for the `lighty-intent-ran-cco:ran` domain and
  always produces a feasible `ran-cco-plan` (target cells + an illustrative A1 policy body), so
  `dry-run` on a RAN CCO intent works today and is the intended way to preview these intents.
* No `DomainActuator` for the `ran` domain is registered with lighty-intent-core. Calling
  `approve-intent` on a RAN CCO intent will therefore fail with a clear "no actuator registered
  for domain lighty-intent-ran-cco:ran" error from lighty-intent-core &mdash; nothing here talks to
  a real Near-RT RIC as part of the intent lifecycle.
* `A1PolicyClient` / `HttpA1PolicyClient` exist and are unit-tested against a loopback HTTP
  server, but nothing calls them from the compiler or from lighty-intent-core yet.
* `RanAssuranceSource` implements `AssuranceSource`, but it is backed purely by an in-memory map
  fed through its own `ingestVesEvent(String)` method; there is no deployed HTTP endpoint, and
  lighty-intent-core does not poll or register this source either.

In short: the interfaces are real, they compile, and they are tested in isolation, but actuation
and assurance are explicitly deferred follow-up work.

## Follow-up work (not in this version)

* A real A1 policy-type schema (registered with the Near-RT RIC's policy-type catalog) in place
  of `RanCcoCompiler`'s placeholder `a1-policy-type-id`
  (`org.o-ran-sc.ies.trafficsteering:1.0.0`, chosen only because it is plausible-looking).
* Real O1 PM/VES event parsing in `RanAssuranceSource`, in place of the minimal illustrative JSON
  shape `ingestVesEvent(String)` currently accepts
  (`{"cellId": ..., "prbUtilizationPercent": ...}`, optionally batched under `"measurements"`).
* Registering a `DomainActuator` for `lighty-intent-ran-cco:ran` with lighty-intent-core, backed
  by `HttpA1PolicyClient`, so `approve-intent` can actually create/delete A1 policies.
* A real ingest endpoint (HTTP or otherwise) feeding `RanAssuranceSource.ingestVesEvent`.
* Conflict detection between RAN CCO intents targeting overlapping cells (`RanCcoCompiler.compile`
  currently accepts `activeIntents` but does not check against it).
