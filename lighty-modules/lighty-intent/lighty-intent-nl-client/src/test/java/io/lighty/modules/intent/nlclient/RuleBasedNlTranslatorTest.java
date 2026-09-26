/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.nlclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lighty.modules.intent.api.TranslationResult;
import org.junit.Test;

public class RuleBasedNlTranslatorTest {

    private final RuleBasedNlTranslator translator = new RuleBasedNlTranslator();

    @Test
    public void draftsTransportQosAndIsolationIntent() {
        final String nlText = "Ensure at least 200 Mbps between site-A and site-B, "
                + "with the traffic isolated, and keep latency under 20 ms.";

        final TranslationResult result = translator.translate(nlText, null);

        assertEquals(TranslationResult.Status.INTENT_DRAFTED, result.getStatus());
        final JsonObject intent = firstIntent(result.getDraftIntentJson());

        assertEquals("lighty-intent-transport:transport", intent.get("domain").getAsString());
        assertEquals("NL_SLM", intent.get("source").getAsString());
        assertEquals(nlText, intent.get("original-nl-text").getAsString());

        final JsonArray targets = firstExpectation(intent).getAsJsonArray("expectation-target");
        assertEquals(3, targets.size());

        final JsonObject bandwidth = findTarget(targets, "lighty-intent-transport:min-bandwidth");
        assertEquals("site-A-to-site-B", bandwidth.get("expectation-object-instance").getAsString());
        assertEquals("IS_GREATER_THAN", bandwidth.get("target-condition").getAsString());
        assertEquals("200", bandwidth.getAsJsonArray("target-value-range").get(0).getAsString());
        assertEquals("Mbps", bandwidth.get("unit").getAsString());

        final JsonObject latency = findTarget(targets, "lighty-intent-transport:max-latency");
        assertEquals("site-A-to-site-B", latency.get("expectation-object-instance").getAsString());
        assertEquals("IS_LESS_THAN", latency.get("target-condition").getAsString());
        assertEquals("20", latency.getAsJsonArray("target-value-range").get(0).getAsString());
        assertEquals("ms", latency.get("unit").getAsString());

        final JsonObject isolation = findTarget(targets, "lighty-intent-transport:traffic-isolation");
        assertEquals("site-A-to-site-B", isolation.get("expectation-object-instance").getAsString());
        assertEquals("IS_EQUAL_TO", isolation.get("target-condition").getAsString());
        assertEquals("true", isolation.getAsJsonArray("target-value-range").get(0).getAsString());
    }

    @Test
    public void draftsRanCcoCoverageIntent() {
        final String nlText = "Keep PRB utilization below 80% for cell-123.";

        final TranslationResult result = translator.translate(nlText, null);

        assertEquals(TranslationResult.Status.INTENT_DRAFTED, result.getStatus());
        final JsonObject intent = firstIntent(result.getDraftIntentJson());

        assertEquals("lighty-intent-ran-cco:ran", intent.get("domain").getAsString());

        final JsonArray targets = firstExpectation(intent).getAsJsonArray("expectation-target");
        assertEquals(1, targets.size());

        final JsonObject prbTarget = targets.get(0).getAsJsonObject();
        assertEquals("lighty-intent-ran-cco:prb-utilization-max",
                prbTarget.get("expectation-object-type").getAsString());
        assertEquals("cell-123", prbTarget.get("expectation-object-instance").getAsString());
        assertEquals("IS_LESS_THAN", prbTarget.get("target-condition").getAsString());
        assertEquals("80", prbTarget.getAsJsonArray("target-value-range").get(0).getAsString());
        assertEquals("%", prbTarget.get("unit").getAsString());
    }

    @Test
    public void asksForClarificationWhenNothingRecognizable() {
        final TranslationResult result = translator.translate("Please say hello to the network team.", null);

        assertEquals(TranslationResult.Status.CLARIFICATION_NEEDED, result.getStatus());
        assertFalse(result.getClarificationQuestions().isEmpty());
    }

    @Test
    public void asksForClarificationOnBlankInput() {
        final TranslationResult result = translator.translate("   ", null);

        assertEquals(TranslationResult.Status.CLARIFICATION_NEEDED, result.getStatus());
        assertFalse(result.getClarificationQuestions().isEmpty());
    }

    private JsonObject firstIntent(final String draftIntentJson) {
        final JsonObject root = JsonParser.parseString(draftIntentJson).getAsJsonObject();
        return root.getAsJsonArray("lighty-intent:intent").get(0).getAsJsonObject();
    }

    private JsonObject firstExpectation(final JsonObject intent) {
        return intent.getAsJsonArray("expectation").get(0).getAsJsonObject();
    }

    private JsonObject findTarget(final JsonArray targets, final String objectType) {
        for (int i = 0; i < targets.size(); i++) {
            final JsonObject target = targets.get(i).getAsJsonObject();
            if (objectType.equals(target.get("expectation-object-type").getAsString())) {
                return target;
            }
        }
        throw new AssertionError("target not found: " + objectType);
    }
}
