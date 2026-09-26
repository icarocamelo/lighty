/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran.a1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpA1PolicyClientTest {

    private HttpServer server;
    private HttpA1PolicyClient client;
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
        client = new HttpA1PolicyClient("http://localhost:" + server.getAddress().getPort());
    }

    @After
    public void stopLoopbackServer() {
        server.stop(0);
    }

    @Test
    public void createPolicySendsPutToPolicyPathWithBody() throws Exception {
        client.createPolicy("traffic-steering:1.0.0", "intent-1", "{\"cellIdList\":[\"Cell-1\"]}");

        final CapturedRequest request = capturedRequests.poll(5, TimeUnit.SECONDS);
        assertEquals("PUT", request.method());
        // HttpExchange#getRequestURI()#getPath() returns the percent-decoded path, so the
        // encoded ':' the client actually put on the wire (%3A) shows up decoded here.
        assertEquals("/A1-P/v2/policytypes/traffic-steering:1.0.0/policies/intent-1", request.path());
        assertEquals("{\"cellIdList\":[\"Cell-1\"]}", request.body());
    }

    @Test
    public void deletePolicySendsDeleteToPolicyPath() throws Exception {
        client.deletePolicy("traffic-steering:1.0.0", "intent-1");

        final CapturedRequest request = capturedRequests.poll(5, TimeUnit.SECONDS);
        assertEquals("DELETE", request.method());
        assertEquals("/A1-P/v2/policytypes/traffic-steering:1.0.0/policies/intent-1", request.path());
        assertEquals("", request.body());
    }

    @Test
    public void nonSuccessStatusFailsTheCallWithIoException() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/A1-P/v2/policytypes/", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        client = new HttpA1PolicyClient("http://localhost:" + server.getAddress().getPort());

        try {
            client.createPolicy("traffic-steering:1.0.0", "intent-1", "{}");
            fail("expected IOException on a non-2xx response");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("500"));
        }
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
