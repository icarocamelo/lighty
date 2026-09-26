/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MinBandwidth;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.Transport;

public class IntentValidatorTest {

    private static final Set<String> KNOWN_DOMAINS = Set.of("lighty-intent-transport:transport");

    @Test
    public void validIntentHasNoProblems() {
        final var target = new ExpectationTargetBuilder()
                .withKey(new ExpectationTargetKey("link-A", MinBandwidth.VALUE))
                .setTargetValueRange(Set.of("200"))
                .setUnit("Mbps")
                .build();
        final var expectation = new ExpectationBuilder()
                .withKey(new ExpectationKey("exp-1"))
                .setExpectationTarget(Map.of(target.key(), target))
                .build();
        final var intent = new IntentBuilder()
                .setIntentId("intent-1")
                .setDomain(Transport.VALUE)
                .setExpectation(Map.<ExpectationKey, Expectation>of(expectation.key(), expectation))
                .build();

        assertTrue(IntentValidator.validate(intent, KNOWN_DOMAINS).isEmpty());
    }

    @Test
    public void missingDomainIsReported() {
        final var intent = new IntentBuilder().setIntentId("intent-1").build();
        final List<String> problems = IntentValidator.validate(intent, KNOWN_DOMAINS);
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("no domain"));
    }

    @Test
    public void unregisteredDomainIsReported() {
        final var intent = new IntentBuilder()
                .setIntentId("intent-1")
                .setDomain(Transport.VALUE)
                .build();
        final List<String> problems = IntentValidator.validate(intent, Set.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("No IntentCompiler")));
    }

    @Test
    public void expectationWithNoTargetsIsReported() {
        final var expectation = new ExpectationBuilder().withKey(new ExpectationKey("exp-1")).build();
        final var intent = new IntentBuilder()
                .setIntentId("intent-1")
                .setDomain(Transport.VALUE)
                .setExpectation(Map.of(expectation.key(), expectation))
                .build();
        final List<String> problems = IntentValidator.validate(intent, KNOWN_DOMAINS);
        assertTrue(problems.stream().anyMatch(p -> p.contains("no expectation-targets")));
    }
}
