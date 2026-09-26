/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import java.util.List;

/**
 * Outcome of running {@link IntentPipeline} (validate + conflict-detect + compile) on one
 * intent. Covers everything {@code dry-run} and the listener's auto-validate need to report,
 * whether the intent failed before reaching a compiler at all (bad domain, no expectations,
 * rule-based conflict) or was compiled and found infeasible by its own compiler.
 */
final class PipelineResult {

    private final boolean feasible;
    private final String domainKey;
    private final String renderedConfigJson;
    private final List<String> problems;

    private PipelineResult(final boolean feasible, final String domainKey, final String renderedConfigJson,
            final List<String> problems) {
        this.feasible = feasible;
        this.domainKey = domainKey;
        this.renderedConfigJson = renderedConfigJson;
        this.problems = List.copyOf(problems);
    }

    static PipelineResult invalid(final List<String> problems) {
        return new PipelineResult(false, null, null, problems);
    }

    static PipelineResult infeasible(final String domainKey, final List<String> problems) {
        return new PipelineResult(false, domainKey, null, problems);
    }

    static PipelineResult feasible(final String domainKey, final String renderedConfigJson) {
        return new PipelineResult(true, domainKey, renderedConfigJson, List.of());
    }

    boolean isFeasible() {
        return feasible;
    }

    String getDomainKey() {
        return domainKey;
    }

    String getRenderedConfigJson() {
        return renderedConfigJson;
    }

    List<String> getProblems() {
        return problems;
    }
}
