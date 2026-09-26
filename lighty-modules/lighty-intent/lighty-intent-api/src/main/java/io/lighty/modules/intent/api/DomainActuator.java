/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Applies (or removes) a {@link CompiledPlan} on the network for one domain.
 *
 * <p>Implementations are registered with lighty-intent-core keyed by {@link #domain()},
 * matching an {@link IntentCompiler} of the same domain. lighty-intent-core calls
 * {@link #apply(String, CompiledPlan)} only after an intent has been approved
 * ({@code approve-intent}), never during {@code dry-run}.
 */
public interface DomainActuator {

    /**
     * @return the domain identity this actuator handles; must equal the {@link
     *     IntentCompiler#domain()} of the compiler producing the plans it is given.
     */
    String domain();

    /**
     * Applies {@code plan} to the network on behalf of {@code intentId}.
     *
     * @param intentId the intent the plan was compiled from, for logging/correlation
     * @param plan a feasible plan previously returned by the matching {@link IntentCompiler}
     * @return a future that completes once the plan has been applied, or fails with the
     *     underlying transport exception (NETCONF/gNMI) on error
     */
    ListenableFuture<Void> apply(String intentId, CompiledPlan plan);

    /**
     * Removes whatever {@link #apply(String, CompiledPlan)} previously applied for
     * {@code intentId} (e.g. when the intent is suspended or deleted).
     *
     * @param intentId the intent whose actuated configuration should be withdrawn
     * @return a future that completes once the configuration has been removed
     */
    ListenableFuture<Void> remove(String intentId);
}
