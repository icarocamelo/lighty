/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.IntentCompiler;
import java.util.Map;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;

/**
 * Validates, conflict-checks and compiles one intent. Shared by the CONFIG-datastore listener
 * (which runs this automatically on every create/update, per lighty-intent.yang's DRAFT ->
 * VALIDATED/AWAITING_APPROVAL/FAILED transitions) and the {@code dry-run} RPC (which re-runs it
 * on demand as a pure preview). Neither caller lets this class touch the network: that only
 * happens in {@code approve-intent}, via the matching {@code DomainActuator}.
 */
final class IntentPipeline {

    private final IntentStoreAccess store;
    private final Map<String, IntentCompiler> compilersByDomain;
    private final ConflictDetector conflictDetector;

    IntentPipeline(final IntentStoreAccess store, final Map<String, IntentCompiler> compilersByDomain,
            final ConflictDetector conflictDetector) {
        this.store = store;
        this.compilersByDomain = compilersByDomain;
        this.conflictDetector = conflictDetector;
    }

    PipelineResult run(final Intent intent) {
        final var validationProblems = IntentValidator.validate(intent, compilersByDomain.keySet());
        if (!validationProblems.isEmpty()) {
            return PipelineResult.invalid(validationProblems);
        }

        final String domainKey = DomainKeys.keyOf(intent.getDomain());
        final var activeIntents = store.readActiveIntents(intent.getIntentId());

        final var ruleConflicts = conflictDetector.checkConflicts(intent, activeIntents);
        if (!ruleConflicts.isEmpty()) {
            return PipelineResult.infeasible(domainKey, ruleConflicts);
        }

        final IntentCompiler compiler = compilersByDomain.get(domainKey);
        final CompiledPlan plan = compiler.compile(intent, activeIntents);
        if (!plan.isFeasible()) {
            return PipelineResult.infeasible(domainKey, plan.getConflicts());
        }
        return PipelineResult.feasible(domainKey, plan.getRenderedConfigJson());
    }
}
