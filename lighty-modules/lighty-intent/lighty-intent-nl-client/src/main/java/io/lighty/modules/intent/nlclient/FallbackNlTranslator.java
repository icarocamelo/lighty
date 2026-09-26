/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.nlclient;

import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.api.TranslationResult;
import java.util.Objects;

/**
 * Composite {@link NlTranslator} that tries a primary translator (typically
 * {@link SidecarNlTranslator}) and, only when it reports {@link TranslationResult.Status#ERROR},
 * falls back to a secondary translator (typically {@link RuleBasedNlTranslator}).
 *
 * <p>A {@code CLARIFICATION_NEEDED} or {@code INTENT_DRAFTED} result from the primary
 * translator is returned as-is: falling back only makes sense when the primary translator
 * could not be reached or failed outright, not when it understood the request well enough to
 * ask a clarifying question.
 */
public final class FallbackNlTranslator implements NlTranslator {

    private final NlTranslator primary;
    private final NlTranslator secondary;

    public FallbackNlTranslator(final NlTranslator primary, final NlTranslator secondary) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
    }

    @Override
    public TranslationResult translate(final String nlText, final String contextHint) {
        final TranslationResult primaryResult = primary.translate(nlText, contextHint);
        if (primaryResult.getStatus() == TranslationResult.Status.ERROR) {
            return secondary.translate(nlText, contextHint);
        }
        return primaryResult;
    }
}
