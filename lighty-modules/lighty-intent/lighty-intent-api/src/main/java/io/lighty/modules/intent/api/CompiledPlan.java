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
 * Result of {@link IntentCompiler#compile(String, Object, java.util.Collection)}: the
 * domain-specific configuration rendered from an intent, plus any conflicts found against
 * currently active intents. Carries no reference back to the source intent; the caller
 * (lighty-intent-core) associates it with an intent-id.
 *
 * <p>{@code renderedConfigJson} is RFC 7951 JSON of the domain's compiled-config YANG
 * container (e.g. {@code lighty-intent-transport:transport-qos-config}). It is opaque to
 * lighty-intent-core: only the {@link DomainActuator} for the same domain knows how to
 * apply it to a device.
 */
public final class CompiledPlan {

    private final String domain;
    private final String renderedConfigJson;
    private final List<String> conflicts;
    private final boolean feasible;

    public CompiledPlan(final String domain, final String renderedConfigJson,
            final List<String> conflicts, final boolean feasible) {
        this.domain = domain;
        this.renderedConfigJson = renderedConfigJson;
        this.conflicts = List.copyOf(conflicts);
        this.feasible = feasible;
    }

    public static CompiledPlan feasible(final String domain, final String renderedConfigJson) {
        return new CompiledPlan(domain, renderedConfigJson, List.of(), true);
    }

    public static CompiledPlan infeasible(final String domain, final List<String> conflicts) {
        return new CompiledPlan(domain, null, conflicts, false);
    }

    public String getDomain() {
        return domain;
    }

    public String getRenderedConfigJson() {
        return renderedConfigJson;
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    public boolean isFeasible() {
        return feasible;
    }
}
