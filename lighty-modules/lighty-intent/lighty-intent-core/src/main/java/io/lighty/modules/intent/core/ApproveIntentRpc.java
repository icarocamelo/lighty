/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import com.google.common.util.concurrent.ListenableFuture;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.DomainActuator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.DateAndTime;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ApproveIntent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ApproveIntentInput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ApproveIntentOutput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ApproveIntentOutputBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yangtools.yang.common.ErrorTag;
import org.opendaylight.yangtools.yang.common.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backs the {@code approve-intent} RPC. Only intents in {@code AWAITING_APPROVAL} are
 * actuated; the pipeline is re-run right before actuation (not just trusted from the last
 * {@code dry-run}/auto-validate) so a conflict introduced by another intent in the meantime is
 * still caught. Actuation is awaited synchronously (bounded by {@link #APPLY_TIMEOUT_SECONDS})
 * so the RPC always returns the intent's final lifecycle-state, not just DEPLOYING.
 */
final class ApproveIntentRpc implements ApproveIntent {

    private static final Logger LOG = LoggerFactory.getLogger(ApproveIntentRpc.class);
    private static final long APPLY_TIMEOUT_SECONDS = 30;

    private final IntentStoreAccess store;
    private final IntentPipeline pipeline;
    private final Map<String, DomainActuator> actuatorsByDomain;

    ApproveIntentRpc(final IntentStoreAccess store, final IntentPipeline pipeline,
            final Map<String, DomainActuator> actuatorsByDomain) {
        this.store = store;
        this.pipeline = pipeline;
        this.actuatorsByDomain = actuatorsByDomain;
    }

    @Override
    public ListenableFuture<RpcResult<ApproveIntentOutput>> invoke(final ApproveIntentInput input) {
        final String intentId = input.getIntentId();
        final var configIntent = store.readConfigIntent(intentId);
        if (configIntent.isEmpty()) {
            return RpcResultBuilder.<ApproveIntentOutput>failed()
                    .withError(ErrorType.APPLICATION, ErrorTag.DATA_MISSING, "Intent " + intentId + " does not exist.")
                    .buildFuture();
        }

        final var opsIntent = store.readOperationalIntent(intentId);
        final IntentLifecycleState currentState = opsIntent.map(i -> i.getLifecycleState())
                .orElse(IntentLifecycleState.DRAFT);
        if (currentState != IntentLifecycleState.AWAITINGAPPROVAL) {
            LOG.warn("approve-intent called for {} while in state {}; ignoring.", intentId, currentState);
            return RpcResultBuilder.success(new ApproveIntentOutputBuilder()
                    .setLifecycleState(currentState).build()).buildFuture();
        }

        final PipelineResult result = pipeline.run(configIntent.get());
        if (!result.isFeasible()) {
            mergeState(intentId, IntentLifecycleState.FAILED,
                    "Re-validation before approval failed: " + String.join("; ", result.getProblems()), null);
            return RpcResultBuilder.success(new ApproveIntentOutputBuilder()
                    .setLifecycleState(IntentLifecycleState.FAILED).build()).buildFuture();
        }

        mergeState(intentId, IntentLifecycleState.DEPLOYING, "Deploying compiled plan.", null);

        final DomainActuator actuator = actuatorsByDomain.get(result.getDomainKey());
        if (actuator == null) {
            mergeState(intentId, IntentLifecycleState.FAILED,
                    "No DomainActuator is registered for domain " + result.getDomainKey() + ".", null);
            return RpcResultBuilder.success(new ApproveIntentOutputBuilder()
                    .setLifecycleState(IntentLifecycleState.FAILED).build()).buildFuture();
        }

        final CompiledPlan plan = CompiledPlan.feasible(result.getDomainKey(), result.getRenderedConfigJson());
        try {
            actuator.apply(intentId, plan).get(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            mergeState(intentId, IntentLifecycleState.ACTIVE, "Deployed successfully.", input.getApprovedBy());
            return RpcResultBuilder.success(new ApproveIntentOutputBuilder()
                    .setLifecycleState(IntentLifecycleState.ACTIVE).build()).buildFuture();
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Actuation failed for intent {}", intentId, e);
            mergeState(intentId, IntentLifecycleState.FAILED, "Actuation failed: " + e.getMessage(),
                    input.getApprovedBy());
            return RpcResultBuilder.success(new ApproveIntentOutputBuilder()
                    .setLifecycleState(IntentLifecycleState.FAILED).build()).buildFuture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while actuating intent {}", intentId, e);
            mergeState(intentId, IntentLifecycleState.FAILED, "Actuation interrupted.", input.getApprovedBy());
            return RpcResultBuilder.success(new ApproveIntentOutputBuilder()
                    .setLifecycleState(IntentLifecycleState.FAILED).build()).buildFuture();
        }
    }

    private void mergeState(final String intentId, final IntentLifecycleState state, final String detail,
            final String approvedBy) {
        final var builder = new IntentBuilder()
                .setLifecycleState(state)
                .setLifecycleDetail(detail)
                .setLastUpdatedTimestamp(new DateAndTime(OffsetDateTime.now(ZoneOffset.UTC).toString()));
        if (approvedBy != null) {
            builder.setApprovedBy(approvedBy);
        }
        store.mergeOperational(intentId, builder);
    }
}
