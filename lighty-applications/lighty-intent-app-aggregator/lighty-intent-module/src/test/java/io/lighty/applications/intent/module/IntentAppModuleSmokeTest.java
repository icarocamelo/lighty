/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lighty.applications.intent.module.config.IntentAppConfigUtils;
import io.lighty.modules.gnmi.simulatordevice.config.GnmiSimulatorConfiguration;
import io.lighty.modules.gnmi.simulatordevice.impl.SimulatedGnmiDevice;
import io.lighty.modules.gnmi.simulatordevice.utils.GnmiSimulatorConfUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke test that starts the whole {@code IntentAppModule} (LightyController + RESTCONF +
 * lighty-intent-core, wired up with the transport/RAN-CCO compilers, the gNMI actuator, and the
 * NL translator chain) against the default configuration, backed by a real in-process gNMI
 * device simulator ({@code lighty-gnmi-device-simulator}) standing in for the configured
 * transport target - mirroring the "start a real backing service in the test" pattern
 * {@code lighty-intent-transport}'s own {@code GnmiTransportActuatorIT} already uses - then
 * drives one full intent lifecycle over RESTCONF end to end: submit, dry-run, approve.
 */
class IntentAppModuleSmokeTest {

    private static final String BASE_URI = "http://127.0.0.1:8888/restconf";
    private static final String CONTENT_TYPE = "application/yang-data+json";
    private static final String SIMULATOR_CONFIG = "/json/simulator_config.json";
    // Matches GnmiTargetConfig's own defaults (host 127.0.0.1, port 10161, plaintext).
    private static final String GNMI_TARGET_HOST = "127.0.0.1";
    private static final int GNMI_TARGET_PORT = 10161;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private SimulatedGnmiDevice gnmiTarget;
    private IntentAppModule intentAppModule;

    @BeforeEach
    void setUp() throws Exception {
        final GnmiSimulatorConfiguration simulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(getClass().getResourceAsStream(SIMULATOR_CONFIG));
        simulatorConfiguration.setTargetAddress(GNMI_TARGET_HOST);
        simulatorConfiguration.setTargetPort(GNMI_TARGET_PORT);
        simulatorConfiguration.setUsePlaintext(true);
        gnmiTarget = new SimulatedGnmiDevice(simulatorConfiguration);
        gnmiTarget.start();

        intentAppModule = new IntentAppModule(IntentAppConfigUtils.loadDefaultConfig());
        assertTrue(intentAppModule.initModules(), "IntentAppModule failed to initialize");
    }

    @AfterEach
    void tearDown() {
        if (intentAppModule != null) {
            assertTrue(intentAppModule.close(), "IntentAppModule failed to shut down cleanly");
        }
        if (gnmiTarget != null) {
            gnmiTarget.stop();
        }
    }

    @Test
    void transportIntentLifecycleDeploysOverGnmi() throws Exception {
        final String intentId = "intent-smoke-transport-1";
        final String intentJson = "{\n"
                + "  \"lighty-intent:intent\": [ {\n"
                + "    \"intent-id\": \"" + intentId + "\",\n"
                + "    \"intent-name\": \"Smoke test transport intent\",\n"
                + "    \"domain\": \"lighty-intent-transport:transport\",\n"
                + "    \"source\": \"API\",\n"
                + "    \"expectation\": [ {\n"
                + "      \"expectation-id\": \"exp-1\",\n"
                + "      \"expectation-target\": [ {\n"
                + "        \"expectation-object-type\": \"lighty-intent-transport:min-bandwidth\",\n"
                + "        \"expectation-object-instance\": \"eth0\",\n"
                + "        \"target-condition\": \"IS_GREATER_THAN\",\n"
                + "        \"target-value-range\": [\"100\"],\n"
                + "        \"unit\": \"Mbps\"\n"
                + "      } ]\n"
                + "    } ]\n"
                + "  } ]\n"
                + "}";
        final var postResponse = httpClient.send(postRequest(BASE_URI + "/data/lighty-intent:intents",
                intentJson), BodyHandlers.ofString());
        assertEquals(HttpStatus.CREATED_201, postResponse.statusCode());

        waitForLifecycleState(intentId, "AWAITING_APPROVAL");

        final var dryRunResponse = httpClient.send(postRequest(BASE_URI + "/operations/lighty-intent:dry-run",
                "{\"input\": {\"intent-id\": \"" + intentId + "\"}}"), BodyHandlers.ofString());
        assertEquals(HttpStatus.OK_200, dryRunResponse.statusCode());
        final JsonNode dryRunOutput = mapper.readTree(dryRunResponse.body()).path("lighty-intent:output");
        assertTrue(dryRunOutput.path("feasible").asBoolean(), "expected sample intent to compile to a feasible plan");
        assertTrue(dryRunOutput.path("rendered-config-json").asText().contains("min-bandwidth-mbps"));

        final var approveResponse = httpClient.send(postRequest(BASE_URI + "/operations/lighty-intent:approve-intent",
                "{\"input\": {\"intent-id\": \"" + intentId + "\", \"approved-by\": \"smoke-test\"}}"),
                BodyHandlers.ofString());
        assertEquals(HttpStatus.OK_200, approveResponse.statusCode());
        final String lifecycleState = mapper.readTree(approveResponse.body()).path("lighty-intent:output")
                .path("lifecycle-state").asText();
        assertEquals("ACTIVE", lifecycleState, "expected the plan to actually deploy over the gNMI simulator");
    }

    @Test
    void ranCcoIntentDryRunsButHasNoRegisteredActuator() throws Exception {
        final String intentId = "intent-smoke-ran-1";
        final String intentJson = "{\n"
                + "  \"lighty-intent:intent\": [ {\n"
                + "    \"intent-id\": \"" + intentId + "\",\n"
                + "    \"intent-name\": \"Smoke test RAN CCO intent\",\n"
                + "    \"domain\": \"lighty-intent-ran-cco:ran\",\n"
                + "    \"source\": \"API\",\n"
                + "    \"expectation\": [ {\n"
                + "      \"expectation-id\": \"exp-1\",\n"
                + "      \"expectation-target\": [ {\n"
                + "        \"expectation-object-type\": \"lighty-intent-ran-cco:prb-utilization-max\",\n"
                + "        \"expectation-object-instance\": \"cell-1\",\n"
                + "        \"target-condition\": \"IS_LESS_THAN\",\n"
                + "        \"target-value-range\": [\"70\"],\n"
                + "        \"unit\": \"%\"\n"
                + "      } ]\n"
                + "    } ]\n"
                + "  } ]\n"
                + "}";
        final var postResponse = httpClient.send(postRequest(BASE_URI + "/data/lighty-intent:intents",
                intentJson), BodyHandlers.ofString());
        assertEquals(HttpStatus.CREATED_201, postResponse.statusCode());

        waitForLifecycleState(intentId, "AWAITING_APPROVAL");

        final var approveResponse = httpClient.send(postRequest(BASE_URI + "/operations/lighty-intent:approve-intent",
                "{\"input\": {\"intent-id\": \"" + intentId + "\", \"approved-by\": \"smoke-test\"}}"),
                BodyHandlers.ofString());
        assertEquals(HttpStatus.OK_200, approveResponse.statusCode());
        final String lifecycleState = mapper.readTree(approveResponse.body()).path("lighty-intent:output")
                .path("lifecycle-state").asText();
        assertEquals("FAILED", lifecycleState, "expected approve-intent on a 'ran' domain intent to fail cleanly,"
                + " as no DomainActuator is registered for it in this version");
    }

    @Test
    void translateNlFallsBackToRuleBasedTranslatorWhenSidecarIsUnreachable() throws Exception {
        // No lighty-intent-slm sidecar is running in this test; FallbackNlTranslator must fall
        // back from SidecarNlTranslator (unreachable at the default localhost:8000) to
        // RuleBasedNlTranslator and still draft an intent for recognizable phrasing.
        final var response = httpClient.send(postRequest(BASE_URI + "/operations/lighty-intent:translate-nl",
                "{\"input\": {\"nl-text\": \"Ensure at least 100 Mbps between site-A and site-B\"}}"),
                BodyHandlers.ofString());
        assertEquals(HttpStatus.OK_200, response.statusCode());
        final JsonNode output = mapper.readTree(response.body()).path("lighty-intent:output");
        assertEquals("INTENT_DRAFTED", output.path("status").asText());
        assertTrue(output.path("draft-intent-json").asText().contains("lighty-intent-transport:min-bandwidth"));
    }

    private void waitForLifecycleState(final String intentId, final String expectedState) throws Exception {
        // Generous: the single-node pekko-clustered datastore's shard leader election can still
        // be settling for a few seconds right after LightyController/CommunityRestConf report
        // started, so the very first RESTCONF write/read can be slow.
        final long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        String lastState = null;
        while (System.currentTimeMillis() < deadline) {
            // content=all: lifecycle-state/active-plan are config-false leaves, written only to
            // the OPERATIONAL datastore by lighty-intent-core; a plain GET here defaults to the
            // CONFIG datastore only and would never show them.
            final var response = httpClient.send(getRequest(BASE_URI + "/data/lighty-intent:intents/intent="
                    + intentId + "?content=all"), BodyHandlers.ofString());
            if (response.statusCode() == HttpStatus.OK_200) {
                final JsonNode intent = mapper.readTree(response.body())
                        .path("lighty-intent:intent").path(0);
                lastState = intent.path("lifecycle-state").asText();
                if (expectedState.equals(lastState)) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        fail("Intent " + intentId + " did not reach lifecycle-state " + expectedState
                + " in time (last observed: " + lastState + ")");
    }

    private static HttpRequest getRequest(final String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Accept", CONTENT_TYPE)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
    }

    private static HttpRequest postRequest(final String uri, final String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", CONTENT_TYPE)
                .header("Accept", CONTENT_TYPE)
                .timeout(Duration.ofSeconds(30))
                .POST(BodyPublishers.ofString(body))
                .build();
    }
}
