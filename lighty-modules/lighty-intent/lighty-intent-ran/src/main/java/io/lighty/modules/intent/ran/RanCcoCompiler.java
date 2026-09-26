/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.IntentCompiler;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.CellCoverageTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.CellEdgeThroughputMin;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.PrbUtilizationMax;

/**
 * {@link IntentCompiler} for the RAN Coverage/Capacity-Optimization (CCO) domain
 * (module {@code lighty-intent-ran-cco}, identity {@code ran}).
 *
 * <p><b>P2/P3 skeleton.</b> This compiler walks an intent's expectation targets and renders a
 * {@code lighty-intent-ran-cco:ran-cco-plan} (RFC 7951 JSON) so {@code dry-run} has something real
 * to preview: the set of target cells, and an illustrative A1 policy body wrapping them. It never
 * talks to a Near-RT RIC itself &mdash; that is {@link io.lighty.modules.intent.ran.a1.A1PolicyClient}'s
 * job, and no {@code DomainActuator} for this domain is registered with lighty-intent-core in this
 * version, so a compiled plan is never actually actuated (see this module's README.md).
 *
 * <p>The compiler always returns a feasible plan: this skeleton does not implement conflict
 * detection against {@code activeIntents} yet, it is accepted (and currently ignored) so the
 * {@link IntentCompiler} contract is satisfied and conflict logic can be added later without an
 * API change.
 */
public final class RanCcoCompiler implements IntentCompiler {

    /**
     * Module-qualified identity value of the {@code ran} intent-domain, as an intent's
     * {@code domain} leaf and this compiler's {@link #domain()} must agree on.
     */
    public static final String DOMAIN = "lighty-intent-ran-cco:ran";

    /**
     * Illustrative A1 policy-type id used until a real O-RAN SC / vendor A1 policy-type schema is
     * registered for CCO. Picking the actual policy type (and its JSON schema) is follow-up work;
     * any plausible-looking identifier works for this skeleton since nothing actuates it yet.
     */
    static final String PLACEHOLDER_A1_POLICY_TYPE_ID = "org.o-ran-sc.ies.trafficsteering:1.0.0";

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public CompiledPlan compile(final Intent intent, final Collection<Intent> activeIntents) {
        // activeIntents is part of the IntentCompiler contract (for conflict detection); this
        // skeleton does not yet implement RAN CCO conflict rules, so it is accepted but unused.
        final Set<String> targetCells = collectTargetCells(intent);
        final String a1PolicyJson = renderA1PolicyJson(targetCells);
        final String renderedConfigJson = renderRanCcoPlanJson(targetCells, PLACEHOLDER_A1_POLICY_TYPE_ID, a1PolicyJson);
        return CompiledPlan.feasible(DOMAIN, renderedConfigJson);
    }

    /**
     * Collects the (deduplicated, order-preserving) set of target-cell ids from every expectation
     * target of {@code intent} whose {@code expectation-object-type} is one of this domain's
     * identities ({@code prb-utilization-max}, {@code cell-coverage-target},
     * {@code cell-edge-throughput-min}).
     */
    private static Set<String> collectTargetCells(final Intent intent) {
        final Set<String> targetCells = new LinkedHashSet<>();
        for (final Expectation expectation : intent.nonnullExpectation().values()) {
            for (final ExpectationTarget target : expectation.nonnullExpectationTarget().values()) {
                if (isRanCcoExpectationObjectType(target) && target.getExpectationObjectInstance() != null) {
                    targetCells.add(target.getExpectationObjectInstance());
                }
            }
        }
        return targetCells;
    }

    private static boolean isRanCcoExpectationObjectType(final ExpectationTarget target) {
        final var type = target.getExpectationObjectType();
        return type instanceof PrbUtilizationMax
            || type instanceof CellCoverageTarget
            || type instanceof CellEdgeThroughputMin;
    }

    /**
     * Renders the illustrative A1 policy object body embedding the collected target cells. The
     * real schema depends on {@link #PLACEHOLDER_A1_POLICY_TYPE_ID} and is follow-up work; this is
     * only meant to make a {@code dry-run} plan preview readable end-to-end.
     */
    private static String renderA1PolicyJson(final Set<String> targetCells) {
        final JsonObject policy = new JsonObject();
        final JsonArray scope = new JsonArray();
        targetCells.forEach(scope::add);
        policy.add("cellIdList", scope);
        policy.addProperty("note", "illustrative CCO A1 policy body, pending a real policy-type schema");
        return GSON.toJson(policy);
    }

    /**
     * Renders the {@code lighty-intent-ran-cco:ran-cco-plan} container as RFC 7951 JSON.
     */
    private static String renderRanCcoPlanJson(final Set<String> targetCells, final String a1PolicyTypeId,
            final String a1PolicyJson) {
        final JsonObject plan = new JsonObject();
        final JsonArray cells = new JsonArray();
        targetCells.forEach(cells::add);
        plan.add("target-cell", cells);
        plan.addProperty("a1-policy-type-id", a1PolicyTypeId);
        plan.addProperty("a1-policy-json", a1PolicyJson);

        final JsonObject root = new JsonObject();
        root.add("lighty-intent-ran-cco:ran-cco-plan", plan);
        return GSON.toJson(root);
    }

    private static final Gson GSON = new Gson();
}
