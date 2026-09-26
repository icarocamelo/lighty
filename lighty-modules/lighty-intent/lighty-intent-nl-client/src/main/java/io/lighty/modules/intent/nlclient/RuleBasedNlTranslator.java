/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.nlclient;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.api.TranslationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline, regex/keyword-based {@link NlTranslator}. Covers exactly the two
 * MVP use cases: transport QoS/isolation phrasing (min-bandwidth, max-latency,
 * traffic-isolation) and RAN coverage/capacity phrasing (prb-utilization-max).
 *
 * <p>Never guesses: when the input contains no recognizable numeric target or keyword for
 * either use case, {@link #translate(String, String)} returns
 * {@link TranslationResult#clarificationNeeded(List)} instead of drafting a low-confidence
 * intent. This translator is meant to be used as the fallback behind
 * {@link SidecarNlTranslator} (see {@link FallbackNlTranslator}), not as the primary
 * translator.
 */
public final class RuleBasedNlTranslator implements NlTranslator {

    private static final String DEFAULT_INSTANCE = "unspecified";

    private static final Pattern MBPS_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*mbps", Pattern.CASE_INSENSITIVE);
    private static final Pattern LATENCY_MS_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ms\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISOLATION_PATTERN =
            Pattern.compile("\\b(?:isolate[d]?|dedicated)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRB_UTILIZATION_PATTERN =
            Pattern.compile("prb\\s+utilization", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern BETWEEN_AND_PATTERN =
            Pattern.compile("\\bbetween\\s+([\\w-]+)\\s+and\\s+([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_PATTERN = Pattern.compile("\\bfor\\s+([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private final Gson gson = new Gson();

    @Override
    public TranslationResult translate(final String nlText, final String contextHint) {
        if (nlText == null || nlText.isBlank()) {
            return TranslationResult.clarificationNeeded(
                    List.of("The request text is empty; please describe the desired network behaviour."));
        }
        if (PRB_UTILIZATION_PATTERN.matcher(nlText).find()) {
            return translateRanCco(nlText);
        }
        return translateTransport(nlText);
    }

    private TranslationResult translateRanCco(final String nlText) {
        final Matcher percentMatcher = PERCENT_PATTERN.matcher(nlText);
        if (!percentMatcher.find()) {
            return TranslationResult.clarificationNeeded(
                    List.of("What PRB utilization percentage threshold should be enforced?"));
        }
        final String value = percentMatcher.group(1);
        final String instance = extractInstance(nlText);

        final JsonObject target = newTarget("lighty-intent-ran-cco:prb-utilization-max", instance,
                "IS_LESS_THAN", value, "%");

        final String draftJson = buildDraftJson(nlText, "lighty-intent-ran-cco:ran", List.of(target));
        return TranslationResult.drafted(draftJson, 0.7);
    }

    private TranslationResult translateTransport(final String nlText) {
        final List<JsonObject> targets = new ArrayList<>();
        final String instance = extractInstance(nlText);

        final Matcher mbpsMatcher = MBPS_PATTERN.matcher(nlText);
        if (mbpsMatcher.find()) {
            targets.add(newTarget("lighty-intent-transport:min-bandwidth", instance,
                    "IS_GREATER_THAN", mbpsMatcher.group(1), "Mbps"));
        }

        final Matcher latencyMatcher = LATENCY_MS_PATTERN.matcher(nlText);
        if (latencyMatcher.find()) {
            targets.add(newTarget("lighty-intent-transport:max-latency", instance,
                    "IS_LESS_THAN", latencyMatcher.group(1), "ms"));
        }

        if (ISOLATION_PATTERN.matcher(nlText).find()) {
            targets.add(newTarget("lighty-intent-transport:traffic-isolation", instance,
                    "IS_EQUAL_TO", "true", null));
        }

        if (targets.isEmpty()) {
            return TranslationResult.clarificationNeeded(List.of(
                    "Could not identify a bandwidth (Mbps), latency (ms), or isolation requirement; "
                            + "please state a numeric target or say the traffic should be isolated/dedicated."));
        }

        final String draftJson = buildDraftJson(nlText, "lighty-intent-transport:transport", targets);
        return TranslationResult.drafted(draftJson, 0.75);
    }

    private JsonObject newTarget(final String objectType, final String instance, final String condition,
            final String value, final String unit) {
        final JsonObject target = new JsonObject();
        target.addProperty("expectation-object-type", objectType);
        target.addProperty("expectation-object-instance", instance);
        target.addProperty("target-condition", condition);
        final JsonArray valueRange = new JsonArray();
        valueRange.add(value);
        target.add("target-value-range", valueRange);
        if (unit != null) {
            target.addProperty("unit", unit);
        }
        return target;
    }

    private String extractInstance(final String nlText) {
        final Matcher between = BETWEEN_AND_PATTERN.matcher(nlText);
        if (between.find()) {
            return between.group(1) + "-to-" + between.group(2);
        }
        final Matcher forMatcher = FOR_PATTERN.matcher(nlText);
        if (forMatcher.find()) {
            return forMatcher.group(1);
        }
        return DEFAULT_INSTANCE;
    }

    private String buildDraftJson(final String nlText, final String domain, final List<JsonObject> targets) {
        final JsonObject intent = new JsonObject();
        intent.addProperty("intent-id", UUID.randomUUID().toString());
        intent.addProperty("intent-name", "NL-drafted intent");
        intent.addProperty("domain", domain);
        intent.addProperty("source", "NL_SLM");
        intent.addProperty("original-nl-text", nlText);

        final JsonObject expectation = new JsonObject();
        expectation.addProperty("expectation-id", UUID.randomUUID().toString());
        expectation.addProperty("expectation-verb", "ENSURE");
        final JsonArray targetArray = new JsonArray();
        targets.forEach(targetArray::add);
        expectation.add("expectation-target", targetArray);

        final JsonArray expectationArray = new JsonArray();
        expectationArray.add(expectation);
        intent.add("expectation", expectationArray);

        final JsonArray intentArray = new JsonArray();
        intentArray.add(intent);

        final JsonObject root = new JsonObject();
        root.add("lighty-intent:intent", intentArray);

        return gson.toJson(root);
    }
}
