/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.lighty.core.controller.api.LightyController;
import io.lighty.core.controller.impl.LightyControllerBuilder;
import io.lighty.core.controller.impl.util.ControllerConfigUtils;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.DomainActuator;
import io.lighty.modules.intent.api.IntentCompiler;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ApproveIntentInputBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.DryRunInputBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.RejectIntentInputBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MinBandwidth;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.Transport;
import org.opendaylight.yangtools.binding.meta.YangModuleInfo;

/**
 * End-to-end test against a real, in-memory lighty controller (same pattern as
 * {@code io.lighty.modules.southbound.netconf.tests.LightyTestUtils} elsewhere in this repo):
 * proves the CONFIG-datastore listener actually fires through a live {@code DataBroker}, and
 * that {@code dry-run}/{@code approve-intent}/{@code reject-intent} drive the lifecycle
 * correctly against a fake {@link IntentCompiler}/{@link DomainActuator} pair.
 */
public class IntentLightyModuleTest {

    private static final String DOMAIN_KEY = "lighty-intent-transport:transport";
    private static final long TIMEOUT_SECONDS = 30;

    private LightyController controller;
    private IntentLightyModule module;
    private RecordingActuator actuator;
    private IntentStoreAccess store;
    private IntentPipeline pipeline;

    @Before
    public void setUp() throws Exception {
        final Set<YangModuleInfo> models = new HashSet<>();
        models.add(org.opendaylight.yang.svc.v1.urn.lighty.intent.rev260926.YangModuleInfoImpl.getInstance());
        models.add(org.opendaylight.yang.svc.v1.urn.lighty.intent.transport.rev260926.YangModuleInfoImpl.getInstance());

        controller = new LightyControllerBuilder()
                .from(ControllerConfigUtils.getDefaultSingleNodeConfiguration(models))
                .build();
        controller.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        actuator = new RecordingActuator();
        final IntentCompiler compiler = new FakeCompiler();
        final DataBroker dataBroker = controller.getServices().getBindingDataBroker();

        module = new IntentLightyModule(dataBroker, controller.getServices().getRpcProviderService(),
                Map.of(DOMAIN_KEY, compiler), Map.of(DOMAIN_KEY, actuator), Map.of(), List.of(), null);
        module.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Same wiring lighty-intent-core builds internally; constructed again here so the test
        // can drive dry-run/approve-intent/reject-intent directly without RPC-registry lookup.
        store = new IntentStoreAccess(dataBroker);
        pipeline = new IntentPipeline(store, Map.of(DOMAIN_KEY, compiler), new ConflictDetector(List.of()));
    }

    @After
    public void tearDown() {
        if (module != null) {
            module.shutdown(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (controller != null) {
            controller.shutdown(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    public void submitDryRunApproveReachesActive() throws Exception {
        final DataBroker dataBroker = controller.getServices().getBindingDataBroker();
        writeIntent(dataBroker, "intent-1");

        final Intent afterAutoValidate = awaitState(dataBroker, "intent-1", IntentLifecycleState.AWAITINGAPPROVAL);
        assertNotNull(afterAutoValidate.getActivePlan());
        assertEquals(Boolean.TRUE, afterAutoValidate.getActivePlan().getFeasible());

        final var dryRunOutput = new DryRunRpc(store, pipeline)
                .invoke(new DryRunInputBuilder().setIntentId("intent-1").build())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResult();
        assertEquals(Boolean.TRUE, dryRunOutput.getFeasible());
        assertNotNull(dryRunOutput.getRenderedConfigJson());

        final var approveOutput = new ApproveIntentRpc(store, pipeline, Map.of(DOMAIN_KEY, actuator))
                .invoke(new ApproveIntentInputBuilder().setIntentId("intent-1").setApprovedBy("test-operator")
                        .build())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResult();
        assertEquals(IntentLifecycleState.ACTIVE, approveOutput.getLifecycleState());
        assertTrue(actuator.applied.get());

        final Intent afterApprove = store.readOperationalIntent("intent-1").orElseThrow();
        assertEquals(IntentLifecycleState.ACTIVE, afterApprove.getLifecycleState());
        assertEquals("test-operator", afterApprove.getApprovedBy());
    }

    @Test
    public void rejectIntentNeverActuates() throws Exception {
        final DataBroker dataBroker = controller.getServices().getBindingDataBroker();
        writeIntent(dataBroker, "intent-2");
        awaitState(dataBroker, "intent-2", IntentLifecycleState.AWAITINGAPPROVAL);

        final var rejectOutput = new RejectIntentRpc(store)
                .invoke(new RejectIntentInputBuilder().setIntentId("intent-2").setReason("not needed").build())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResult();
        assertEquals(IntentLifecycleState.FAILED, rejectOutput.getLifecycleState());
        assertTrue(actuator.applyCalls.get() == 0);
    }

    @Test
    public void deletingActiveIntentCallsActuatorRemove() throws Exception {
        final DataBroker dataBroker = controller.getServices().getBindingDataBroker();
        writeIntent(dataBroker, "intent-3");
        awaitState(dataBroker, "intent-3", IntentLifecycleState.AWAITINGAPPROVAL);
        new ApproveIntentRpc(store, pipeline, Map.of(DOMAIN_KEY, actuator))
                .invoke(new ApproveIntentInputBuilder().setIntentId("intent-3").setApprovedBy("t").build())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitState(dataBroker, "intent-3", IntentLifecycleState.ACTIVE);

        final var writeTx = dataBroker.newWriteOnlyTransaction();
        writeTx.delete(LogicalDatastoreType.CONFIGURATION, IntentStoreAccess.intentPath("intent-3"));
        writeTx.commit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        awaitTrue(() -> actuator.removeCalls.get() > 0);
    }

    private static void writeIntent(final DataBroker dataBroker, final String intentId) throws Exception {
        final var target = new ExpectationTargetBuilder()
                .withKey(new ExpectationTargetKey("link-A", MinBandwidth.VALUE))
                .setTargetValueRange(Set.of("200"))
                .setUnit("Mbps")
                .build();
        final var expectation = new ExpectationBuilder()
                .withKey(new ExpectationKey("exp-1"))
                .setExpectationTarget(Map.of(target.key(), target))
                .build();
        final Intent intent = new IntentBuilder()
                .setIntentId(intentId)
                .setDomain(Transport.VALUE)
                .setExpectation(Map.<ExpectationKey, org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents
                        .intent.Expectation>of(expectation.key(), expectation))
                .build();
        final var writeTx = dataBroker.newWriteOnlyTransaction();
        writeTx.put(LogicalDatastoreType.CONFIGURATION, IntentStoreAccess.intentPath(intentId), intent);
        writeTx.commit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Intent awaitState(final DataBroker dataBroker, final String intentId,
            final IntentLifecycleState expected) throws Exception {
        final IntentStoreAccess access = new IntentStoreAccess(dataBroker);
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            final var opsIntent = access.readOperationalIntent(intentId);
            if (opsIntent.isPresent() && opsIntent.get().getLifecycleState() == expected) {
                return opsIntent.get();
            }
            Thread.sleep(100);
        }
        fail("Intent " + intentId + " did not reach state " + expected + " in time.");
        throw new AssertionError("unreachable");
    }

    private static void awaitTrue(final java.util.function.BooleanSupplier condition) throws Exception {
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Condition not met in time.");
    }

    private static final class FakeCompiler implements IntentCompiler {
        @Override
        public String domain() {
            return DOMAIN_KEY;
        }

        @Override
        public CompiledPlan compile(final Intent intent, final Collection<Intent> activeIntents) {
            return CompiledPlan.feasible(DOMAIN_KEY, "{\"qos-policy\":[{\"qos-class\":\"link-A\"}]}");
        }
    }

    private static final class RecordingActuator implements DomainActuator {
        private final AtomicReference<Boolean> applied = new AtomicReference<>(false);
        private final java.util.concurrent.atomic.AtomicInteger applyCalls = new java.util.concurrent.atomic
                .AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicInteger removeCalls = new java.util.concurrent.atomic
                .AtomicInteger(0);

        @Override
        public String domain() {
            return DOMAIN_KEY;
        }

        @Override
        public ListenableFuture<Void> apply(final String intentId, final CompiledPlan plan) {
            applied.set(true);
            applyCalls.incrementAndGet();
            return Futures.immediateVoidFuture();
        }

        @Override
        public ListenableFuture<Void> remove(final String intentId) {
            removeCalls.incrementAndGet();
            return Futures.immediateVoidFuture();
        }
    }
}
