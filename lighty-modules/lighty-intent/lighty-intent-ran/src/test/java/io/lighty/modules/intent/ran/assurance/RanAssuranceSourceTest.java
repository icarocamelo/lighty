/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran.assurance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.lighty.modules.intent.api.ObservedTarget;
import io.lighty.modules.intent.ran.RanCcoCompiler;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.FulfilmentStatus;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.PrbUtilizationMax;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.Ran;
import org.opendaylight.yangtools.binding.util.BindingMap;

public class RanAssuranceSourceTest {

    private final RanAssuranceSource assuranceSource = new RanAssuranceSource();

    @Test
    public void domainMatchesRanCcoCompilerDomain() {
        assertEquals(RanCcoCompiler.DOMAIN, assuranceSource.domain());
    }

    @Test
    public void observeReturnsUnknownWhenNothingIngestedYetForTheCell() {
        final Intent intent = intentWithPrbTarget("Cell-1", "80");

        final List<ObservedTarget> observed = assuranceSource.observe(intent);

        assertEquals(1, observed.size());
        assertEquals("Cell-1", observed.get(0).getExpectationObjectInstance());
        assertEquals(FulfilmentStatus.UNKNOWN, observed.get(0).getFulfilmentStatus());
    }

    @Test
    public void ingestSingleMeasurementUpdatesObservedValueAndFulfilment() {
        assuranceSource.ingestVesEvent("{\"cellId\": \"Cell-1\", \"prbUtilizationPercent\": 63.5}");
        final Intent intent = intentWithPrbTarget("Cell-1", "80");

        final ObservedTarget observed = assuranceSource.observe(intent).get(0);

        assertEquals("63.5", observed.getObservedValue());
        assertEquals(FulfilmentStatus.FULFILLED, observed.getFulfilmentStatus());
    }

    @Test
    public void ingestReportsNotFulfilledWhenObservedValueExceedsThreshold() {
        assuranceSource.ingestVesEvent("{\"cellId\": \"Cell-1\", \"prbUtilizationPercent\": 95.0}");
        final Intent intent = intentWithPrbTarget("Cell-1", "80");

        final ObservedTarget observed = assuranceSource.observe(intent).get(0);

        assertEquals(FulfilmentStatus.NOTFULFILLED, observed.getFulfilmentStatus());
    }

    @Test
    public void ingestBatchOfMeasurementsUpdatesEveryCell() {
        assuranceSource.ingestVesEvent("{\"measurements\": ["
            + "{\"cellId\": \"Cell-1\", \"prbUtilizationPercent\": 10.0},"
            + "{\"cellId\": \"Cell-2\", \"prbUtilizationPercent\": 90.0}"
            + "]}");

        final Intent intent = intentWithPrbTargets("Cell-1", "50", "Cell-2", "50");
        final List<ObservedTarget> observed = assuranceSource.observe(intent);

        assertEquals(2, observed.size());
        final ObservedTarget cell1 = findByCell(observed, "Cell-1");
        final ObservedTarget cell2 = findByCell(observed, "Cell-2");
        assertEquals(FulfilmentStatus.FULFILLED, cell1.getFulfilmentStatus());
        assertEquals(FulfilmentStatus.NOTFULFILLED, cell2.getFulfilmentStatus());
    }

    @Test
    public void malformedIngestPayloadIsIgnoredRatherThanThrowing() {
        assuranceSource.ingestVesEvent("not json at all");
        assuranceSource.ingestVesEvent("{\"cellId\": \"Cell-1\"}");
        assuranceSource.ingestVesEvent("42");

        final Intent intent = intentWithPrbTarget("Cell-1", "80");
        assertTrue(assuranceSource.observe(intent).get(0).getFulfilmentStatus() == FulfilmentStatus.UNKNOWN);
    }

    private static ObservedTarget findByCell(final List<ObservedTarget> observed, final String cellId) {
        return observed.stream()
            .filter(target -> cellId.equals(target.getExpectationObjectInstance()))
            .findFirst()
            .orElseThrow();
    }

    private static Intent intentWithPrbTarget(final String cellId, final String maxPercent) {
        return intentWithPrbTargets(cellId, maxPercent);
    }

    private static Intent intentWithPrbTargets(final String... cellIdAndMaxPercentPairs) {
        final var targets = new java.util.ArrayList<ExpectationTarget>();
        for (int i = 0; i < cellIdAndMaxPercentPairs.length; i += 2) {
            targets.add(new ExpectationTargetBuilder()
                .setExpectationObjectType(PrbUtilizationMax.VALUE)
                .setExpectationObjectInstance(cellIdAndMaxPercentPairs[i])
                .setTargetValueRange(Set.of(cellIdAndMaxPercentPairs[i + 1]))
                .build());
        }
        final Expectation expectation = new ExpectationBuilder()
            .setExpectationId("expectation-1")
            .setExpectationTarget(BindingMap.of(targets))
            .build();
        return new IntentBuilder()
            .setIntentId("intent-1")
            .setDomain(Ran.VALUE)
            .setExpectation(BindingMap.of(expectation))
            .build();
    }
}
