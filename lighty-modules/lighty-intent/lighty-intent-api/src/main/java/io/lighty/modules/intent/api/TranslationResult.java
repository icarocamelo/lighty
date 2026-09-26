/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

import java.util.List;

/**
 * Result of {@link NlTranslator#translate(String, String)}. Mirrors the output of the
 * {@code translate-nl} RPC (module {@code lighty-intent}) so that
 * {@code IntentLightyModule} can copy it into the RPC output almost verbatim.
 */
public final class TranslationResult {

    /** Mirrors the {@code translate-nl} RPC output's {@code status} leaf. */
    public enum Status {
        INTENT_DRAFTED,
        CLARIFICATION_NEEDED,
        ERROR
    }

    private final Status status;
    private final String draftIntentJson;
    private final List<String> clarificationQuestions;
    private final double confidence;

    private TranslationResult(final Status status, final String draftIntentJson,
            final List<String> clarificationQuestions, final double confidence) {
        this.status = status;
        this.draftIntentJson = draftIntentJson;
        this.clarificationQuestions = List.copyOf(clarificationQuestions);
        this.confidence = confidence;
    }

    public static TranslationResult drafted(final String draftIntentJson, final double confidence) {
        return new TranslationResult(Status.INTENT_DRAFTED, draftIntentJson, List.of(), confidence);
    }

    public static TranslationResult clarificationNeeded(final List<String> questions) {
        return new TranslationResult(Status.CLARIFICATION_NEEDED, null, questions, 0.0);
    }

    public static TranslationResult error() {
        return new TranslationResult(Status.ERROR, null, List.of(), 0.0);
    }

    public Status getStatus() {
        return status;
    }

    public String getDraftIntentJson() {
        return draftIntentJson;
    }

    public List<String> getClarificationQuestions() {
        return clarificationQuestions;
    }

    public double getConfidence() {
        return confidence;
    }
}
