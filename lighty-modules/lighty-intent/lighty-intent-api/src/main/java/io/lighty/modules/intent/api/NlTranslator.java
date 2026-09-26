/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

/**
 * Turns a natural-language operator request into a draft intent, or a set of clarifying
 * questions. Backs the {@code translate-nl} RPC.
 *
 * <p>Implementations never write to the intent store and never actuate anything: a
 * translator only produces a draft that the caller (an operator, or an automated client)
 * still has to review and submit. This keeps the SLM out of the network's write path,
 * whatever model or service backs a given implementation.
 */
public interface NlTranslator {

    /**
     * @param nlText the operator's request, verbatim
     * @param contextHint optional freeform hint (a domain or site name) to help disambiguate
     *     the request; may be {@code null}
     * @return the translation outcome: a draft intent, clarifying questions, or an error
     */
    TranslationResult translate(String nlText, String contextHint);
}
