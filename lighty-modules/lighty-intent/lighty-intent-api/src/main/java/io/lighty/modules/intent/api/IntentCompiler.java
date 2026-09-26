/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

import java.util.Collection;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;

/**
 * Compiles one intent's expectations into a domain-specific {@link CompiledPlan}.
 *
 * <p>Implementations are registered with lighty-intent-core keyed by the value of
 * {@link #domain()}, which must match the {@code identityref} value an intent declares
 * as its {@code domain} leaf (e.g. {@code lighty-intent-transport:transport}). A compiler
 * never talks to the network; that is the job of the matching {@link DomainActuator}. This
 * split is what makes {@code dry-run} safe to call repeatedly.
 */
public interface IntentCompiler {

    /**
     * @return the domain identity (module-qualified name, e.g.
     *     {@code "lighty-intent-transport:transport"}) this compiler handles.
     */
    String domain();

    /**
     * Compiles {@code intent} into a {@link CompiledPlan}, checking it against
     * {@code activeIntents} (every other intent currently in state {@code ACTIVE},
     * {@code AWAITING_APPROVAL} or {@code DEPLOYING}) for conflicts.
     *
     * <p>Must not have any side effect outside the returned {@link CompiledPlan}: no network
     * calls, no writes to the intent store. Called from both the {@code dry-run} RPC and
     * from the actuation path after {@code approve-intent}.
     *
     * @param intent the intent to compile; its {@code domain} leaf equals {@link #domain()}
     * @param activeIntents other intents currently deployed or being deployed, for conflict
     *     checking; never includes {@code intent} itself
     * @return a feasible plan with rendered configuration, or an infeasible plan carrying
     *     the reasons (validation problems, conflicts) it could not be compiled
     */
    CompiledPlan compile(Intent intent, Collection<Intent> activeIntents);
}
