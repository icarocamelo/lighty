/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.DomainActuator;
import io.lighty.modules.intent.ran.a1.A1PolicyClient;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DomainActuator} for the RAN Coverage/Capacity-Optimization (CCO) domain
 * ({@link RanCcoCompiler#DOMAIN}): turns a {@link RanCcoCompiler}-produced
 * {@code lighty-intent-ran-cco:ran-cco-plan} into a real A1 policy on a Near-RT RIC, via
 * {@link A1PolicyClient}.
 *
 * <p>{@code apply} parses {@link CompiledPlan#getRenderedConfigJson()} back into a {@link
 * JsonObject} (the only place in this module that needs to destructure rendered JSON rather than
 * just produce or forward it, so it follows {@code RanAssuranceSource.ingestVesEvent}'s
 * {@code JsonParser}/{@code JsonObject} style rather than inventing a new one) to pull out
 * {@code a1-policy-type-id} and {@code a1-policy-json}, then calls {@link
 * A1PolicyClient#createPolicy}. The intent's own id is used as the A1 policy id: simple, unique,
 * and legible in the RIC's own policy listing.
 *
 * <p>{@link A1PolicyClient}'s methods are synchronous and throw checked exceptions, unlike the
 * gNMI connector's native {@link ListenableFuture} API {@code GnmiTransportActuator} builds on.
 * Both {@code ApproveIntentRpc} and {@code IntentChangeListener} only ever await an actuator's
 * future synchronously (with a timeout) regardless of what runs underneath it, so there is
 * nothing to gain from a dedicated executor here: {@link #apply} and {@link #remove} simply make
 * the blocking call inline, on the caller's own thread, and wrap the outcome in {@link
 * Futures#immediateFuture} / {@link Futures#immediateFailedFuture}. This avoids owning an
 * executor (and the shutdown lifecycle that would come with one) for no real concurrency benefit.
 *
 * <p>{@code remove(intentId)} needs the same {@code a1-policy-type-id} used at creation, since
 * the A1-P REST path is scoped by policy type, but the {@link DomainActuator} contract only
 * passes {@code intentId}, not the plan. This class remembers it in an in-memory map populated by
 * {@link #apply}; that memory is lost on restart, same known limitation as {@code
 * RanAssuranceSource}'s own in-memory PRB-utilization map and {@code lighty-intent-core}'s
 * in-memory conflict detection elsewhere in this project.
 */
public final class RanCcoActuator implements DomainActuator {

    /**
     * The domain identity this actuator handles, matching {@link RanCcoCompiler#domain()}.
     */
    public static final String DOMAIN = RanCcoCompiler.DOMAIN;

    private static final Logger LOG = LoggerFactory.getLogger(RanCcoActuator.class);
    private static final String RAN_CCO_PLAN_KEY = "lighty-intent-ran-cco:ran-cco-plan";
    private static final String A1_POLICY_TYPE_ID_KEY = "a1-policy-type-id";
    private static final String A1_POLICY_JSON_KEY = "a1-policy-json";

    private final A1PolicyClient a1PolicyClient;
    private final ConcurrentHashMap<String, String> policyTypeIdByIntentId = new ConcurrentHashMap<>();

    public RanCcoActuator(final A1PolicyClient a1PolicyClient) {
        this.a1PolicyClient = Objects.requireNonNull(a1PolicyClient, "a1PolicyClient");
    }

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public ListenableFuture<Void> apply(final String intentId, final CompiledPlan plan) {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(plan, "plan");
        if (!plan.isFeasible()) {
            throw new IllegalArgumentException(
                    "Cannot apply an infeasible plan for intent " + intentId + ": " + plan.getConflicts());
        }

        final JsonObject ranCcoPlan;
        try {
            ranCcoPlan = parseRanCcoPlan(plan.getRenderedConfigJson());
        } catch (RuntimeException e) {
            return Futures.immediateFailedFuture(new IllegalArgumentException(
                    "Intent " + intentId + "'s compiled plan is not a valid " + RAN_CCO_PLAN_KEY + " JSON object", e));
        }
        final String policyTypeId = requireStringMember(ranCcoPlan, A1_POLICY_TYPE_ID_KEY, intentId);
        final String policyJson = requireStringMember(ranCcoPlan, A1_POLICY_JSON_KEY, intentId);

        LOG.info("Creating A1 policy for intent {} (policy-type-id={})", intentId, policyTypeId);
        try {
            a1PolicyClient.createPolicy(policyTypeId, intentId, policyJson);
        } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Futures.immediateFailedFuture(e);
        }
        // Remembered only after a successful create, so a failed apply never leaves remove()
        // trying to delete a policy that was never actually created.
        policyTypeIdByIntentId.put(intentId, policyTypeId);
        return Futures.immediateFuture(null);
    }

    @Override
    public ListenableFuture<Void> remove(final String intentId) {
        Objects.requireNonNull(intentId, "intentId");
        final String policyTypeId = policyTypeIdByIntentId.remove(intentId);
        if (policyTypeId == null) {
            LOG.debug("No A1 policy on record for intent {}; nothing to remove.", intentId);
            return Futures.immediateFuture(null);
        }

        LOG.info("Deleting A1 policy for intent {} (policy-type-id={})", intentId, policyTypeId);
        try {
            a1PolicyClient.deletePolicy(policyTypeId, intentId);
        } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Futures.immediateFailedFuture(e);
        }
        return Futures.immediateFuture(null);
    }

    private static JsonObject parseRanCcoPlan(final String renderedConfigJson) {
        final JsonObject root = JsonParser.parseString(renderedConfigJson).getAsJsonObject();
        final var ranCcoPlan = root.get(RAN_CCO_PLAN_KEY);
        if (ranCcoPlan == null || !ranCcoPlan.isJsonObject()) {
            throw new IllegalArgumentException("Missing or non-object '" + RAN_CCO_PLAN_KEY + "' member");
        }
        return ranCcoPlan.getAsJsonObject();
    }

    private static String requireStringMember(final JsonObject ranCcoPlan, final String member,
            final String intentId) {
        final var value = ranCcoPlan.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "Intent " + intentId + "'s compiled plan is missing a string '" + member + "' member");
        }
        return value.getAsString();
    }
}
