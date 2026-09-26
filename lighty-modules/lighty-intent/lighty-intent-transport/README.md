<!--
  ~ Copyright (c) 2026 icarocamelo. All Rights Reserved.
  ~
  ~ This program and the accompanying materials are made available under the
  ~ terms of the Eclipse Public License v1.0 which accompanies this distribution,
  ~ and is available at https://www.eclipse.org/legal/epl-v10.html
  -->

# lighty-intent-transport

Transport-domain intent compiler and gNMI actuator for lighty.io's intent-driven management
(IDM) module.

## What it does

This module implements the `io.lighty.modules.intent.api` SPI for the transport domain
(`lighty-intent-transport:transport`, defined in `lighty-intent-transport.yang`):

* **`TransportQosCompiler`** (`IntentCompiler`) walks an intent's `expectation-target`
  entries, grouping the `min-bandwidth` / `max-latency` / `max-packet-loss`
  expectation-object-types that share an `expectation-object-instance` into one
  `qos-policy` list entry, and turning each `traffic-isolation` target into one
  `network-instance` list entry. It runs a narrow conflict check: two intents in
  `ACTIVE`, `AWAITING_APPROVAL` or `DEPLOYING` state that both request
  `traffic-isolation` for the same `expectation-object-instance` but name different
  `network-instance` values conflict. The result is a `CompiledPlan` carrying RFC
  7951-shaped JSON for the `lighty-intent-transport:transport-qos-config` container
  contents (rendered directly with Gson, not through the full YANG binding codec).
* **`GnmiTransportActuator`** (`DomainActuator`) actuates a feasible plan by replacing
  the `transport-qos-config` container at its target with a gNMI `SetRequest` (via
  `RequestBuilder.Type.REPLACE`), and withdraws it on `remove()` with a gNMI delete
  `SetRequest`. It is constructed with one already-connected `SessionProvider`
  (`lighty-gnmi-connector`); routing a single intent's plan across multiple gNMI
  targets/devices is out of scope for this version (see the Javadoc on
  `GnmiTransportActuator` for the follow-up).

## How it fits into the intent pipeline

lighty-intent-core resolves the `IntentCompiler` and `DomainActuator` registered for an
intent's `domain` leaf (here, `lighty-intent-transport:transport`). `dry-run` calls
`TransportQosCompiler.compile(...)` only (no network access, safe to repeat);
`approve-intent` then hands the resulting `CompiledPlan` to
`GnmiTransportActuator.apply(...)`. Removing/suspending the intent calls
`GnmiTransportActuator.remove(...)`.

## P1.5 OpenConfig-vendoring note

`transport-qos-config` is intentionally a lighty-owned subset shaped after OpenConfig's
`openconfig-qos` / `openconfig-acl` / `openconfig-network-instance` containers (qos policy
classes, ACL entries, network-instance/VRF isolation), rather than a vendored copy of those
modules. Vendoring the real OpenConfig QoS/ACL/network-instance tree pulls in a large
transitive dependency graph (`openconfig-policy-types`, `openconfig-routing-policy`,
`openconfig-mpls-types`, `openconfig-if-ip` and others) that lighty does not otherwise
carry; see `docs/intent-driven-management.md`, roadmap item P1.5, for the follow-up to
replace this subset with the real OpenConfig models once vendored.

## Testing

* `TransportQosCompilerTest` (JUnit 5) exercises the compiler directly: QoS grouping,
  traffic-isolation-to-network-instance rendering, and conflict detection, by building
  `Intent`/`Expectation`/`ExpectationTarget` instances with the generated
  `lighty-intent-models` builders.
* `GnmiTransportActuatorIT` (JUnit 5) starts an in-process `SimulatedGnmiDevice`
  (`lighty-gnmi-device-simulator`), compiles a sample intent, applies it over a real gNMI
  session, reads the `transport-qos-config` container back and asserts it round-trips, then
  removes it and asserts a subsequent `Get` fails — mirroring
  `lighty-gnmi-test`'s `SimulatorCrudTest`. The simulator's schema is resolved from the
  `lighty-intent-models` module already on the test classpath (its generated
  `YangModuleInfo`, referenced by namespace/name/revision in
  `src/test/resources/json/simulator_config.json`'s `topLevelModels`), so no additional
  raw `.yang` files or `yangsPath` directory are needed for this module's own models.
