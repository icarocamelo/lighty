/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.ran.a1.HttpA1PolicyClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RanCcoActuatorTest {

    private static final String INTENT_ID = "intent-1";
    private static final String PLAN_JSON = "{\"lighty-intent-ran-cco:ran-cco-plan\":{"
            + "\"target-cell\":[\"Cell-1\"],"
            + "\"a1-policy-type-id\":\"traffic-steering:1.0.0\","
            + "\"a1-policy-json\":\"{\\\"cellIdList\\\":[\\\"Cell-1\\\"]}\"}}";

    private HttpServer server;
    private RanCcoActuator actuator;
    private final BlockingQueue<CapturedRequest> capturedRequests = new ArrayBlockingQueue<>(10);

    @Before
    public void startLoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/A1-P/v2/policytypes/", exchange -> {
            final byte[] body = exchange.getRequestBody().readAllBytes();
            capturedRequests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        actuator = new RanCcoActuator(new HttpA1PolicyClient("http://localhost:" + server.getAddress().getPort()));
    }

    @After
    public void stopLoopbackServer() {
        server.stop(0);
    }

    @Test
    public void applySendsPutWithParsedPolicyTypeAndBody() throws Exception {
        actuator.apply(INTENT_ID, CompiledPlan.feasible(RanCcoActuator.DOMAIN, PLAN_JSON)).get(5, TimeUnit.SECONDS);

        final CapturedRequest request = capturedRequests.poll(5, TimeUnit.SECONDS);
        assertEquals("PUT", request.method());
        assertEquals("/A1-P/v2/policytypes/traffic-steering:1.0.0/policies/" + INTENT_ID, request.path());
        assertEquals("{\"cellIdList\":[\"Cell-1\"]}", request.body());
    }

    @Test
    public void removeSendsDeleteUsingThePolicyTypeRememberedFromApply() throws Exception {
        actuator.apply(INTENT_ID, CompiledPlan.feasible(RanCcoActuator.DOMAIN, PLAN_JSON)).get(5, TimeUnit.SECONDS);
        capturedRequests.poll(5, TimeUnit.SECONDS);

        actuator.remove(INTENT_ID).get(5, TimeUnit.SECONDS);

        final CapturedRequest request = capturedRequests.poll(5, TimeUnit.SECONDS);
        assertEquals("DELETE", request.method());
        assertEquals("/A1-P/v2/policytypes/traffic-steering:1.0.0/policies/" + INTENT_ID, request.path());
    }

    @Test
    public void removeWithoutAPriorApplyIsANoOp() throws Exception {
        actuator.remove("never-applied").get(5, TimeUnit.SECONDS);
        assertNull(capturedRequests.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void applyRejectsAnInfeasiblePlanWithoutAnyHttpCall() throws Exception {
        final CompiledPlan infeasible = CompiledPlan.infeasible(RanCcoActuator.DOMAIN, List.of("conflict"));
        assertThrows(IllegalArgumentException.class, () -> actuator.apply(INTENT_ID, infeasible));
        assertNull(capturedRequests.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void applyFailsTheFutureOnMalformedPlanJson() {
        final CompiledPlan malformed = CompiledPlan.feasible(RanCcoActuator.DOMAIN, "{\"not-a-ran-cco-plan\":{}}");
        final var future = actuator.apply(INTENT_ID, malformed);
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("expected the future to fail on a plan missing ran-cco-plan");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        } catch (Exception e) {
            fail("unexpected exception type: " + e);
        }
    }

    @Test
    public void applyFailsTheFutureOnNonSuccessResponse() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/A1-P/v2/policytypes/", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        actuator = new RanCcoActuator(new HttpA1PolicyClient("http://localhost:" + server.getAddress().getPort()));

        final var future = actuator.apply(INTENT_ID, CompiledPlan.feasible(RanCcoActuator.DOMAIN, PLAN_JSON));
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("expected the future to fail on a 500 response");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
            assertTrue(expected.getCause().getMessage().contains("500"));
        } catch (Exception e) {
            fail("unexpected exception type: " + e);
        }
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
