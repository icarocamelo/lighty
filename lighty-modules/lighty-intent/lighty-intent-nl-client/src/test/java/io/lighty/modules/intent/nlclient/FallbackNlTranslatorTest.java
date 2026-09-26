/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.nlclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.api.TranslationResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class FallbackNlTranslatorTest {

    @Test
    public void returnsPrimaryDraftedResultWithoutCallingSecondary() {
        final TranslationResult primaryResult = TranslationResult.drafted("{}", 0.9);
        final NlTranslator primary = (nlText, contextHint) -> primaryResult;
        final NlTranslator secondary = (nlText, contextHint) -> {
            fail("secondary should not be called when primary succeeds");
            return TranslationResult.error();
        };

        final FallbackNlTranslator fallback = new FallbackNlTranslator(primary, secondary);
        final TranslationResult result = fallback.translate("some text", null);

        assertSame(primaryResult, result);
    }

    @Test
    public void doesNotFallBackOnClarificationNeeded() {
        final TranslationResult clarification = TranslationResult.clarificationNeeded(List.of("q?"));
        final NlTranslator primary = (nlText, contextHint) -> clarification;
        final NlTranslator secondary = (nlText, contextHint) -> {
            fail("secondary should not be called when primary asks for clarification");
            return TranslationResult.error();
        };

        final FallbackNlTranslator fallback = new FallbackNlTranslator(primary, secondary);
        final TranslationResult result = fallback.translate("some text", null);

        assertSame(clarification, result);
    }

    @Test
    public void fallsBackToSecondaryOnPrimaryError() {
        final AtomicInteger secondaryCalls = new AtomicInteger();
        final NlTranslator primary = (nlText, contextHint) -> TranslationResult.error();
        final TranslationResult secondaryResult = TranslationResult.drafted("{}", 0.5);
        final NlTranslator secondary = (nlText, contextHint) -> {
            secondaryCalls.incrementAndGet();
            return secondaryResult;
        };

        final FallbackNlTranslator fallback = new FallbackNlTranslator(primary, secondary);
        final TranslationResult result = fallback.translate("some text", "hint");

        assertEquals(1, secondaryCalls.get());
        assertSame(secondaryResult, result);
    }
}
