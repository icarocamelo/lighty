/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran.assurance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.lighty.modules.intent.api.AssuranceSource;
import io.lighty.modules.intent.api.ObservedTarget;
import io.lighty.modules.intent.ran.RanCcoCompiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.FulfilmentStatus;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.PrbUtilizationMax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AssuranceSource} for the RAN CCO domain (see {@link RanCcoCompiler}), backed by an
 * in-memory map of the latest observed PRB (Physical Resource Block) utilization per cell.
 *
 * <p><b>P2/P3 skeleton.</b> {@link #ingestVesEvent(String)} parses a minimal, illustrative
 * VES-like JSON shape, <em>not</em> a real O1 PM/VES event; a real VES event's
 * {@code commonEventHeader}/{@code measurementFields} structure is follow-up work (see this
 * module's README.md). Nothing feeds this method automatically in this version &mdash; there is
 * no deployed HTTP endpoint for it &mdash; so {@link #observe} only ever sees whatever a caller
 * (e.g. a test, or a future ingest endpoint) has pushed through {@link #ingestVesEvent(String)}.
 */
public final class RanAssuranceSource implements AssuranceSource {

    private static final Logger LOG = LoggerFactory.getLogger(RanAssuranceSource.class);

    /**
     * String form of the {@code prb-utilization-max} expectation-object-type, as carried by
     * {@link ObservedTarget#getExpectationObjectType()} (a plain string there, not an identityref,
     * mirroring {@code /intent-reports/intent-report/target-fulfilment}).
     */
    static final String PRB_UTILIZATION_MAX_TYPE = "lighty-intent-ran-cco:prb-utilization-max";

    private final ConcurrentHashMap<String, Double> latestPrbUtilizationPercentByCell = new ConcurrentHashMap<>();

    @Override
    public String domain() {
        return RanCcoCompiler.DOMAIN;
    }

    /**
     * Ingests one illustrative VES-like JSON payload, updating the latest known PRB utilization
     * for every cell it mentions. The accepted shape is either a single measurement object
     * <pre>{@code {"cellId": "Cell-1", "prbUtilizationPercent": 63.5}}</pre>
     * or several of them under a {@code measurements} array
     * <pre>{@code {"measurements": [{"cellId": "Cell-1", "prbUtilizationPercent": 63.5}, ...]}}</pre>
     * Malformed or unrecognized input is logged and otherwise ignored: this is a best-effort
     * ingest for a skeleton, not a validating one.
     *
     * @param jsonBody the illustrative VES-like JSON payload
     */
    public void ingestVesEvent(final String jsonBody) {
        final JsonElement root;
        try {
            root = JsonParser.parseString(jsonBody);
        } catch (JsonSyntaxException e) {
            LOG.warn("Ignoring malformed VES-like ingest payload: {}", e.getMessage());
            return;
        }
        if (!root.isJsonObject()) {
            LOG.warn("Ignoring VES-like ingest payload that is not a JSON object");
            return;
        }
        final JsonObject rootObject = root.getAsJsonObject();
        if (rootObject.has("measurements") && rootObject.get("measurements").isJsonArray()) {
            for (final JsonElement measurement : rootObject.getAsJsonArray("measurements")) {
                if (measurement.isJsonObject()) {
                    ingestOne(measurement.getAsJsonObject());
                }
            }
        } else {
            ingestOne(rootObject);
        }
    }

    private void ingestOne(final JsonObject measurement) {
        final JsonElement cellIdElement = measurement.get("cellId");
        final JsonElement prbElement = measurement.get("prbUtilizationPercent");
        if (cellIdElement == null || !cellIdElement.isJsonPrimitive() || prbElement == null
                || !prbElement.isJsonPrimitive()) {
            LOG.warn("Ignoring VES-like measurement missing cellId/prbUtilizationPercent: {}", measurement);
            return;
        }
        final String cellId = cellIdElement.getAsString();
        final double prbUtilizationPercent;
        try {
            prbUtilizationPercent = prbElement.getAsDouble();
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring VES-like measurement with non-numeric prbUtilizationPercent for cell {}", cellId);
            return;
        }
        latestPrbUtilizationPercentByCell.put(cellId, prbUtilizationPercent);
    }

    @Override
    public List<ObservedTarget> observe(final Intent intent) {
        final List<ObservedTarget> observed = new ArrayList<>();
        for (final Expectation expectation : intent.nonnullExpectation().values()) {
            for (final ExpectationTarget target : expectation.nonnullExpectationTarget().values()) {
                if (target.getExpectationObjectType() instanceof PrbUtilizationMax) {
                    observed.add(observeOne(target));
                }
            }
        }
        return observed;
    }

    private ObservedTarget observeOne(final ExpectationTarget target) {
        final String cellId = target.getExpectationObjectInstance();
        final Double observedValue = cellId == null ? null : latestPrbUtilizationPercentByCell.get(cellId);
        if (observedValue == null) {
            return new ObservedTarget(PRB_UTILIZATION_MAX_TYPE, cellId, null, FulfilmentStatus.UNKNOWN);
        }
        return new ObservedTarget(PRB_UTILIZATION_MAX_TYPE, cellId, String.valueOf(observedValue),
            fulfilmentStatus(observedValue, target));
    }

    /**
     * Compares {@code observedValue} against the target's first {@code target-value-range} entry,
     * treated as a maximum threshold (matching {@code prb-utilization-max}'s semantics). Anything
     * this skeleton cannot make sense of (no threshold, or a non-numeric one) is reported as
     * {@code UNKNOWN} rather than guessed at.
     */
    private static FulfilmentStatus fulfilmentStatus(final double observedValue, final ExpectationTarget target) {
        final Set<String> targetValueRange = target.getTargetValueRange();
        if (targetValueRange == null || targetValueRange.isEmpty()) {
            return FulfilmentStatus.UNKNOWN;
        }
        final double threshold;
        try {
            threshold = Double.parseDouble(targetValueRange.iterator().next());
        } catch (NumberFormatException e) {
            return FulfilmentStatus.UNKNOWN;
        }
        return observedValue <= threshold ? FulfilmentStatus.FULFILLED : FulfilmentStatus.NOTFULFILLED;
    }
}
