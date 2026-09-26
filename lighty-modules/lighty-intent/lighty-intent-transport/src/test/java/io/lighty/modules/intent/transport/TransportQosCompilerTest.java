/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lighty.modules.intent.api.CompiledPlan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ExpectationObjectType;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MaxLatency;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MaxPacketLoss;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MinBandwidth;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.TrafficIsolation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.Transport;
import org.opendaylight.yangtools.binding.util.BindingMap;

class TransportQosCompilerTest {

    private final TransportQosCompiler compiler = new TransportQosCompiler();

    @Test
    void domainMatchesTransportIdentity() {
        assertEquals("lighty-intent-transport:transport", compiler.domain());
    }

    @Test
    void compilesQosPolicyGroupedByObjectInstance() {
        final Expectation expectation = expectation("exp-1",
                target(MinBandwidth.VALUE, "eth0", "100"),
                target(MaxLatency.VALUE, "eth0", "20"),
                target(MaxPacketLoss.VALUE, "eth0", "0.50"));
        final Intent intent = intent("intent-1", IntentLifecycleState.DRAFT, expectation);

        final CompiledPlan plan = compiler.compile(intent, List.of());

        assertTrue(plan.isFeasible());
        final JsonObject config = JsonParser.parseString(plan.getRenderedConfigJson()).getAsJsonObject();
        final JsonArray qosPolicy = config.getAsJsonArray("qos-policy");
        assertEquals(1, qosPolicy.size());
        final JsonObject entry = qosPolicy.get(0).getAsJsonObject();
        assertEquals("eth0", entry.get("qos-class").getAsString());
        assertEquals(100, entry.get("min-bandwidth-mbps").getAsLong());
        assertEquals(20, entry.get("max-latency-ms").getAsLong());
        assertEquals(0.50, entry.get("max-packet-loss-percent").getAsDouble(), 0.001);
        assertEquals("eth0", entry.getAsJsonArray("applied-interface").get(0).getAsString());
    }

    @Test
    void compilesTrafficIsolationAsNetworkInstance() {
        final Expectation expectation = expectation("exp-1", target(TrafficIsolation.VALUE, "eth1", "vrf-red"));
        final Intent intent = intent("intent-2", IntentLifecycleState.DRAFT, expectation);

        final CompiledPlan plan = compiler.compile(intent, List.of());

        assertTrue(plan.isFeasible());
        final JsonObject config = JsonParser.parseString(plan.getRenderedConfigJson()).getAsJsonObject();
        final JsonArray networkInstance = config.getAsJsonArray("network-instance");
        assertEquals(1, networkInstance.size());
        final JsonObject entry = networkInstance.get(0).getAsJsonObject();
        assertEquals("vrf-red", entry.get("name").getAsString());
        assertEquals("eth1", entry.getAsJsonArray("interface").get(0).getAsString());
    }

    @Test
    void detectsTrafficIsolationConflictAgainstActiveIntent() {
        final Intent activeIntent = intent("intent-active", IntentLifecycleState.ACTIVE,
                expectation("exp-a", target(TrafficIsolation.VALUE, "eth1", "vrf-red")));
        final Intent candidateIntent = intent("intent-new", IntentLifecycleState.DRAFT,
                expectation("exp-b", target(TrafficIsolation.VALUE, "eth1", "vrf-blue")));

        final CompiledPlan plan = compiler.compile(candidateIntent, List.of(activeIntent));

        assertFalse(plan.isFeasible());
        assertEquals(1, plan.getConflicts().size());
        assertTrue(plan.getConflicts().get(0).contains("eth1"));
        assertTrue(plan.getConflicts().get(0).contains("vrf-red"));
        assertTrue(plan.getConflicts().get(0).contains("vrf-blue"));
    }

    @Test
    void noConflictWhenActiveIntentAgreesOnSameNetworkInstance() {
        final Intent activeIntent = intent("intent-active", IntentLifecycleState.ACTIVE,
                expectation("exp-a", target(TrafficIsolation.VALUE, "eth1", "vrf-red")));
        final Intent candidateIntent = intent("intent-new", IntentLifecycleState.DRAFT,
                expectation("exp-b", target(TrafficIsolation.VALUE, "eth1", "vrf-red")));

        final CompiledPlan plan = compiler.compile(candidateIntent, List.of(activeIntent));

        assertTrue(plan.isFeasible());
    }

    @Test
    void ignoresConflictAgainstIntentThatIsNotActive() {
        final Intent draftIntent = intent("intent-draft", IntentLifecycleState.DRAFT,
                expectation("exp-a", target(TrafficIsolation.VALUE, "eth1", "vrf-red")));
        final Intent candidateIntent = intent("intent-new", IntentLifecycleState.DRAFT,
                expectation("exp-b", target(TrafficIsolation.VALUE, "eth1", "vrf-blue")));

        final CompiledPlan plan = compiler.compile(candidateIntent, List.of(draftIntent));

        assertTrue(plan.isFeasible());
    }

    private static ExpectationTarget target(final ExpectationObjectType type, final String objectInstance,
            final String value) {
        return new ExpectationTargetBuilder()
                .setExpectationObjectType(type)
                .setExpectationObjectInstance(objectInstance)
                .setTargetValueRange(Set.of(value))
                .build();
    }

    private static Expectation expectation(final String expectationId, final ExpectationTarget... targets) {
        return new ExpectationBuilder()
                .setExpectationId(expectationId)
                .setExpectationTarget(BindingMap.of(targets))
                .build();
    }

    private static Intent intent(final String intentId, final IntentLifecycleState lifecycleState,
            final Expectation... expectations) {
        return new IntentBuilder()
                .setIntentId(intentId)
                .setDomain(Transport.VALUE)
                .setLifecycleState(lifecycleState)
                .setExpectation(BindingMap.of(expectations))
                .build();
    }
}
