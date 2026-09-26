/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

import java.util.Collection;
import java.util.List;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;

/**
 * A cross-domain conflict check, run by lighty-intent-core's conflict detector before a
 * compiler-specific check. Unlike {@link IntentCompiler}, a single {@link ConflictRule}
 * may look across domains (e.g. a rule that flags a coverage-optimization intent raising
 * cell power in an area an energy-saving intent is dimming).
 *
 * <p>Domain compilers still perform their own, domain-specific conflict checks inside
 * {@link IntentCompiler#compile}; {@link ConflictRule}s are for conflicts that are not
 * specific to one domain's compiled config.
 */
public interface ConflictRule {

    /**
     * @param candidate the intent being validated/dry-run
     * @param activeIntents other intents currently deployed or being deployed
     * @return a human-readable description of each conflict found; empty if none
     */
    List<String> checkConflicts(Intent candidate, Collection<Intent> activeIntents);
}
