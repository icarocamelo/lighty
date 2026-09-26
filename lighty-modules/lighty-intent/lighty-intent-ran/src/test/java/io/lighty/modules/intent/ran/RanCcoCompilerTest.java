/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.lighty.modules.intent.api.CompiledPlan;
import java.util.List;
import org.junit.Test;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.CellCoverageTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.PrbUtilizationMax;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.Ran;
import org.opendaylight.yangtools.binding.util.BindingMap;

public class RanCcoCompilerTest {

    private final RanCcoCompiler compiler = new RanCcoCompiler();

    @Test
    public void domainMatchesRanCcoIdentity() {
        assertEquals("lighty-intent-ran-cco:ran", compiler.domain());
    }

    @Test
    public void compilesFeasiblePlanWithTargetCellsFromMatchingExpectationTargets() {
        final Intent intent = intentWithTargets(
            new ExpectationTargetBuilder()
                .setExpectationObjectType(PrbUtilizationMax.VALUE)
                .setExpectationObjectInstance("Cell-1")
                .build(),
            new ExpectationTargetBuilder()
                .setExpectationObjectType(CellCoverageTarget.VALUE)
                .setExpectationObjectInstance("Cell-2")
                .build());

        final CompiledPlan plan = compiler.compile(intent, List.of());

        assertTrue(plan.isFeasible());
        assertEquals(RanCcoCompiler.DOMAIN, plan.getDomain());
        assertTrue(plan.getConflicts().isEmpty());
        final String json = plan.getRenderedConfigJson();
        assertTrue(json.contains("lighty-intent-ran-cco:ran-cco-plan"));
        assertTrue(json.contains("Cell-1"));
        assertTrue(json.contains("Cell-2"));
        assertTrue(json.contains(RanCcoCompiler.PLACEHOLDER_A1_POLICY_TYPE_ID));
        assertTrue(json.contains("a1-policy-json"));
    }

    @Test
    public void ignoresExpectationTargetsOfUnrelatedExpectationObjectType() {
        final Intent intent = intentWithTargets(
            new ExpectationTargetBuilder()
                .setExpectationObjectType(org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.ExpectationObjectType.VALUE)
                .setExpectationObjectInstance("not-a-ran-target")
                .build());

        final CompiledPlan plan = compiler.compile(intent, List.of());

        assertTrue(plan.isFeasible());
        final String json = plan.getRenderedConfigJson();
        assertTrue(json.contains("\"target-cell\":[]"));
    }

    private static Intent intentWithTargets(
            final org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget... targets) {
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
