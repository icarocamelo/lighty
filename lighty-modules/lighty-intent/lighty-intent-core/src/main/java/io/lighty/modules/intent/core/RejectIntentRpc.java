/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import com.google.common.util.concurrent.ListenableFuture;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.DateAndTime;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.RejectIntent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.RejectIntentInput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.RejectIntentOutput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.RejectIntentOutputBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yangtools.yang.common.ErrorTag;
import org.opendaylight.yangtools.yang.common.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;

/**
 * Backs the {@code reject-intent} RPC: moves the intent straight to {@code FAILED} regardless
 * of its current state, recording the given reason. A rejected intent is never actuated.
 */
final class RejectIntentRpc implements RejectIntent {

    private final IntentStoreAccess store;

    RejectIntentRpc(final IntentStoreAccess store) {
        this.store = store;
    }

    @Override
    public ListenableFuture<RpcResult<RejectIntentOutput>> invoke(final RejectIntentInput input) {
        final String intentId = input.getIntentId();
        if (store.readConfigIntent(intentId).isEmpty()) {
            return RpcResultBuilder.<RejectIntentOutput>failed()
                    .withError(ErrorType.APPLICATION, ErrorTag.DATA_MISSING, "Intent " + intentId + " does not exist.")
                    .buildFuture();
        }

        final String reason = input.getReason() == null ? "Rejected by operator." : "Rejected: " + input.getReason();
        store.mergeOperational(intentId, new IntentBuilder()
                .setLifecycleState(IntentLifecycleState.FAILED)
                .setLifecycleDetail(reason)
                .setLastUpdatedTimestamp(new DateAndTime(OffsetDateTime.now(ZoneOffset.UTC).toString())));

        return RpcResultBuilder.success(new RejectIntentOutputBuilder()
                .setLifecycleState(IntentLifecycleState.FAILED).build()).buildFuture();
    }
}
