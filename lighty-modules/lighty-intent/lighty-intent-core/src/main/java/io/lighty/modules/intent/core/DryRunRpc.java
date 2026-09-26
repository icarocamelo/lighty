/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.Set;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.DryRun;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.DryRunInput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.DryRunOutput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.DryRunOutputBuilder;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;

/**
 * Backs the {@code dry-run} RPC: re-runs {@link IntentPipeline} for the given intent-id and
 * returns the preview. Has no side effect beyond what {@link IntentPipeline} itself does
 * (reading from the datastore); it never writes and never actuates.
 */
final class DryRunRpc implements DryRun {

    private final IntentStoreAccess store;
    private final IntentPipeline pipeline;

    DryRunRpc(final IntentStoreAccess store, final IntentPipeline pipeline) {
        this.store = store;
        this.pipeline = pipeline;
    }

    @Override
    public ListenableFuture<RpcResult<DryRunOutput>> invoke(final DryRunInput input) {
        final String intentId = input.getIntentId();
        final var configIntent = store.readConfigIntent(intentId);
        if (configIntent.isEmpty()) {
            return RpcResultBuilder.success(new DryRunOutputBuilder()
                    .setFeasible(Boolean.FALSE)
                    .setConflict(Set.of("Intent " + intentId + " does not exist."))
                    .build()).buildFuture();
        }

        final PipelineResult result = pipeline.run(configIntent.get());
        final DryRunOutputBuilder output = new DryRunOutputBuilder()
                .setFeasible(result.isFeasible())
                .setDomainCompiler(result.getDomainKey());
        if (result.isFeasible()) {
            output.setRenderedConfigJson(result.getRenderedConfigJson());
        } else {
            output.setConflict(Set.copyOf(result.getProblems()));
        }
        return RpcResultBuilder.success(output.build()).buildFuture();
    }
}
