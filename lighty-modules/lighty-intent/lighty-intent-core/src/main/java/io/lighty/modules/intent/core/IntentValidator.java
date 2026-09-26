/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;

/**
 * Semantic validation of an intent, run before conflict detection and compilation. YANG's own
 * schema validation (mandatory leaves, key uniqueness, identityref base) is enforced by MD-SAL
 * itself on write; this class checks the things the schema alone cannot: whether the intent's
 * domain has a registered compiler, and whether it declares anything to actually compile.
 */
final class IntentValidator {

    private IntentValidator() {
        // utility class
    }

    /**
     * @param intent the intent to validate
     * @param knownDomainKeys the domain keys ({@link DomainKeys#keyOf}) that have a registered
     *     {@code IntentCompiler}
     * @return a human-readable description of each problem found; empty if the intent is valid
     */
    static List<String> validate(final Intent intent, final Set<String> knownDomainKeys) {
        final List<String> problems = new ArrayList<>();

        final var domain = intent.getDomain();
        if (domain == null) {
            problems.add("Intent has no domain.");
        } else {
            final var domainKey = DomainKeys.keyOf(domain);
            if (domainKey == null) {
                problems.add("Domain " + domain.implementedInterface().getSimpleName()
                        + " is not a domain lighty-intent-core recognizes.");
            } else if (!knownDomainKeys.contains(domainKey)) {
                problems.add("No IntentCompiler is registered for domain " + domainKey + ".");
            }
        }

        final var expectations = intent.getExpectation();
        if (expectations == null || expectations.isEmpty()) {
            problems.add("Intent has no expectations.");
        } else {
            for (final var expectation : expectations.values()) {
                final var targets = expectation.getExpectationTarget();
                if (targets == null || targets.isEmpty()) {
                    problems.add("Expectation " + expectation.getExpectationId() + " has no expectation-targets.");
                }
            }
        }

        return problems;
    }
}
