/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import com.google.common.util.concurrent.ListenableFuture;
import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.api.TranslationResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.TranslateNl;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.TranslateNlInput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.TranslateNlOutput;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.TranslateNlOutputBuilder;
import org.opendaylight.yangtools.yang.common.Decimal64;
import org.opendaylight.yangtools.yang.common.ErrorTag;
import org.opendaylight.yangtools.yang.common.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;

/**
 * Backs the {@code translate-nl} RPC by delegating to the configured {@link NlTranslator}
 * (typically a fallback chain: an HTTP client to the SLM sidecar, falling back to a rule-based
 * offline translator). Never writes to the intent store itself: the caller still has to submit
 * the returned draft as a new intent.
 */
final class TranslateNlRpc implements TranslateNl {

    private final NlTranslator translator;

    TranslateNlRpc(final NlTranslator translator) {
        this.translator = translator;
    }

    @Override
    public ListenableFuture<RpcResult<TranslateNlOutput>> invoke(final TranslateNlInput input) {
        if (translator == null) {
            return RpcResultBuilder.<TranslateNlOutput>failed()
                    .withError(ErrorType.APPLICATION, ErrorTag.OPERATION_NOT_SUPPORTED,
                            "No NlTranslator is configured.")
                    .buildFuture();
        }

        final TranslationResult result = translator.translate(input.getNlText(), input.getContextHint());
        final TranslateNlOutputBuilder output = new TranslateNlOutputBuilder();
        switch (result.getStatus()) {
            case INTENT_DRAFTED -> output.setStatus(TranslateNlOutput.Status.INTENTDRAFTED)
                    .setDraftIntentJson(result.getDraftIntentJson())
                    .setConfidence(Decimal64.valueOf(
                            BigDecimal.valueOf(result.getConfidence()).setScale(2, RoundingMode.HALF_UP)));
            case CLARIFICATION_NEEDED -> output.setStatus(TranslateNlOutput.Status.CLARIFICATIONNEEDED)
                    .setClarificationQuestion(Set.copyOf(result.getClarificationQuestions()));
            case ERROR -> output.setStatus(TranslateNlOutput.Status.ERROR);
        }
        return RpcResultBuilder.success(output.build()).buildFuture();
    }
}
