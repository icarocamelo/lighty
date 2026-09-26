/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.nlclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.lighty.modules.intent.api.TranslationResult;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SidecarNlTranslatorTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void translatesSuccessfulDraftedResponseAndSendsExpectedRequest() throws IOException {
        final AtomicReference<String> receivedBody = new AtomicReference<>();
        final AtomicReference<String> receivedContentType = new AtomicReference<>();
        server.createContext("/v1/translate", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));

            final JsonObject response = new JsonObject();
            response.addProperty("status", "INTENT_DRAFTED");
            response.addProperty("draft_intent_json", "{\"lighty-intent:intent\":[]}");
            response.addProperty("confidence", 0.91);
            sendJsonResponse(exchange, 200, response.toString());
        });
        server.start();

        final SidecarNlTranslator translator = new SidecarNlTranslator(baseUrl, Duration.ofSeconds(5));
        final TranslationResult result = translator.translate("ensure 200 Mbps between site-A and site-B",
                "transport");

        assertEquals(TranslationResult.Status.INTENT_DRAFTED, result.getStatus());
        assertEquals("{\"lighty-intent:intent\":[]}", result.getDraftIntentJson());
        assertEquals(0.91, result.getConfidence(), 0.0001);

        assertTrue(receivedContentType.get().startsWith("application/json"));
        final JsonObject sentRequest = JsonParser.parseString(receivedBody.get()).getAsJsonObject();
        assertEquals("ensure 200 Mbps between site-A and site-B", sentRequest.get("text").getAsString());
        assertEquals("transport", sentRequest.get("context_hint").getAsString());
    }

    @Test
    public void translatesClarificationNeededResponse() throws IOException {
        server.createContext("/v1/translate", exchange -> {
            final JsonObject response = new JsonObject();
            response.addProperty("status", "CLARIFICATION_NEEDED");
            final com.google.gson.JsonArray questions = new com.google.gson.JsonArray();
            questions.add("Which site should the bandwidth apply to?");
            response.add("clarification_questions", questions);
            sendJsonResponse(exchange, 200, response.toString());
        });
        server.start();

        final SidecarNlTranslator translator = new SidecarNlTranslator(baseUrl);
        final TranslationResult result = translator.translate("ensure fast internet", null);

        assertEquals(TranslationResult.Status.CLARIFICATION_NEEDED, result.getStatus());
        assertEquals(1, result.getClarificationQuestions().size());
        assertEquals("Which site should the bandwidth apply to?", result.getClarificationQuestions().get(0));
    }

    @Test
    public void returnsErrorOnServerFailureStatus() throws IOException {
        server.createContext("/v1/translate", exchange -> sendJsonResponse(exchange, 500, "not json"));
        server.start();

        final SidecarNlTranslator translator = new SidecarNlTranslator(baseUrl, Duration.ofSeconds(5));
        final TranslationResult result = translator.translate("anything", null);

        assertEquals(TranslationResult.Status.ERROR, result.getStatus());
    }

    @Test
    public void returnsErrorOnMalformedJsonBodyWithHttp200() throws IOException {
        server.createContext("/v1/translate", exchange -> sendJsonResponse(exchange, 200, "{not-valid-json"));
        server.start();

        final SidecarNlTranslator translator = new SidecarNlTranslator(baseUrl);
        final TranslationResult result = translator.translate("anything", null);

        assertEquals(TranslationResult.Status.ERROR, result.getStatus());
    }

    @Test
    public void returnsErrorWhenServerUnreachable() {
        // Free the port straight away so the connection is refused instead of hanging.
        server.stop(0);
        server = null;

        final SidecarNlTranslator translator = new SidecarNlTranslator(baseUrl, Duration.ofSeconds(2));
        final TranslationResult result = translator.translate("anything", null);

        assertEquals(TranslationResult.Status.ERROR, result.getStatus());
    }

    private static void sendJsonResponse(final com.sun.net.httpserver.HttpExchange exchange, final int statusCode,
            final String body) {
        try {
            final byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
