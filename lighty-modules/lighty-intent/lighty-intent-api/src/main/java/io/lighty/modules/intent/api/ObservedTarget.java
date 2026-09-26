/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.api;

import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.FulfilmentStatus;

/**
 * One observed value for an expectation target, produced by an {@link AssuranceSource}.
 * Mirrors a {@code /intent-reports/intent-report/target-fulfilment} list entry (module
 * {@code lighty-intent}).
 */
public final class ObservedTarget {

    private final String expectationObjectType;
    private final String expectationObjectInstance;
    private final String observedValue;
    private final FulfilmentStatus fulfilmentStatus;

    public ObservedTarget(final String expectationObjectType, final String expectationObjectInstance,
            final String observedValue, final FulfilmentStatus fulfilmentStatus) {
        this.expectationObjectType = expectationObjectType;
        this.expectationObjectInstance = expectationObjectInstance;
        this.observedValue = observedValue;
        this.fulfilmentStatus = fulfilmentStatus;
    }

    public String getExpectationObjectType() {
        return expectationObjectType;
    }

    public String getExpectationObjectInstance() {
        return expectationObjectInstance;
    }

    public String getObservedValue() {
        return observedValue;
    }

    public FulfilmentStatus getFulfilmentStatus() {
        return fulfilmentStatus;
    }
}
