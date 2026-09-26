/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

import java.util.List;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;

/**
 * Observes the current value of an intent's expectation targets, for fulfilment assurance.
 * Implementations are registered with lighty-intent-core keyed by {@link #domain()}, matching
 * the domain of the intents they can observe (e.g. gNMI telemetry for transport, O1 PM/VES
 * for RAN).
 *
 * <p>An {@link AssuranceSource} only reports; in this version, lighty-intent-core never
 * automatically re-plans or re-deploys an intent based on its output (see
 * docs/intent-driven-management.md roadmap item P5).
 */
public interface AssuranceSource {

    /**
     * @return the domain identity this source observes, matching an {@link IntentCompiler}'s
     *     {@link IntentCompiler#domain()}.
     */
    String domain();

    /**
     * @param intent an {@code ACTIVE} or {@code DEGRADED} intent of this source's domain
     * @return the currently observed value for each of {@code intent}'s expectation targets
     *     that this source can measure; targets it cannot measure are simply omitted
     */
    List<ObservedTarget> observe(Intent intent);
}
