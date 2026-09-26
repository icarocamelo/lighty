/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import io.lighty.modules.intent.api.ConflictRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;

/**
 * Runs every registered {@link ConflictRule} against a candidate intent. Domain-specific
 * conflicts (e.g. two transport intents wanting different network-instances on the same
 * interface) are the matching {@code IntentCompiler}'s own job, inside {@code compile()}; this
 * class only covers conflicts that are not specific to one domain's compiled config.
 */
final class ConflictDetector {

    private final List<ConflictRule> rules;

    ConflictDetector(final List<ConflictRule> rules) {
        this.rules = List.copyOf(rules);
    }

    List<String> checkConflicts(final Intent candidate, final Collection<Intent> activeIntents) {
        final List<String> conflicts = new ArrayList<>();
        for (final ConflictRule rule : rules) {
            conflicts.addAll(rule.checkConflicts(candidate, activeIntents));
        }
        return conflicts;
    }
}
