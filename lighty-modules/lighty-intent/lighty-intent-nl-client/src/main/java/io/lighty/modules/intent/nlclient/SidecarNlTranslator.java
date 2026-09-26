/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.nlclient;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.api.TranslationResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NlTranslator} backed by an external Python NL-to-intent "SLM sidecar" HTTP service.
 *
 * <p>POSTs {@code {"text": nlText, "context_hint": contextHint}} to
 * {@code <baseUrl>/v1/translate} and expects a JSON response shaped
 * {@code {"status": "INTENT_DRAFTED"|"CLARIFICATION_NEEDED"|"ERROR",
 * "draft_intent_json": "...", "clarification_questions": ["..."], "confidence": 0.0}}.
 *
 * <p>Never throws: any I/O failure, timeout, non-200 response, or malformed response body is
 * logged at WARN and turned into {@link TranslationResult#error()}, so that callers (typically
 * {@link FallbackNlTranslator}) can fall back to an offline translator without having to guard
 * against exceptions.
 */
public final class SidecarNlTranslator implements NlTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarNlTranslator.class);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String TRANSLATE_PATH = "/v1/translate";

    private static final String STATUS_INTENT_DRAFTED = "INTENT_DRAFTED";
    private static final String STATUS_CLARIFICATION_NEEDED = "CLARIFICATION_NEEDED";

    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public SidecarNlTranslator(final String baseUrl) {
        this(baseUrl, DEFAULT_REQUEST_TIMEOUT);
    }

    public SidecarNlTranslator(final String baseUrl, final Duration requestTimeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    @Override
    public TranslationResult translate(final String nlText, final String contextHint) {
        final String requestJson = buildRequestJson(nlText, contextHint);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + TRANSLATE_PATH))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warn("Failed to reach NL SLM sidecar at {}: {}", baseUrl, e.getMessage());
            return TranslationResult.error();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for NL SLM sidecar at {}", baseUrl);
            return TranslationResult.error();
        }

        if (response.statusCode() != 200) {
            LOG.warn("NL SLM sidecar at {} returned non-200 status {}", baseUrl, response.statusCode());
            return TranslationResult.error();
        }

        try {
            return parseResponse(response.body());
        } catch (JsonSyntaxException | IllegalStateException | NullPointerException e) {
            LOG.warn("Malformed response from NL SLM sidecar at {}: {}", baseUrl, e.getMessage());
            return TranslationResult.error();
        }
    }

    private String buildRequestJson(final String nlText, final String contextHint) {
        final JsonObject requestBody = new JsonObject();
        requestBody.addProperty("text", nlText);
        if (contextHint != null) {
            requestBody.addProperty("context_hint", contextHint);
        } else {
            requestBody.add("context_hint", com.google.gson.JsonNull.INSTANCE);
        }
        return gson.toJson(requestBody);
    }

    private TranslationResult parseResponse(final String body) {
        final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        final JsonElement statusElement = json.get("status");
        if (statusElement == null) {
            LOG.warn("NL SLM sidecar response is missing 'status': {}", body);
            return TranslationResult.error();
        }
        final String status = statusElement.getAsString();

        if (STATUS_INTENT_DRAFTED.equals(status)) {
            return toDraftedResult(json, body);
        } else if (STATUS_CLARIFICATION_NEEDED.equals(status)) {
            return toClarificationNeededResult(json);
        } else {
            return TranslationResult.error();
        }
    }

    private TranslationResult toDraftedResult(final JsonObject json, final String body) {
        final JsonElement draftIntentJson = json.get("draft_intent_json");
        if (draftIntentJson == null) {
            LOG.warn("NL SLM sidecar reported INTENT_DRAFTED without 'draft_intent_json': {}", body);
            return TranslationResult.error();
        }
        final JsonElement confidenceElement = json.get("confidence");
        final double confidence = confidenceElement == null ? 0.0 : confidenceElement.getAsDouble();
        return TranslationResult.drafted(draftIntentJson.getAsString(), confidence);
    }

    private TranslationResult toClarificationNeededResult(final JsonObject json) {
        final List<String> questions = new ArrayList<>();
        final JsonElement questionsElement = json.get("clarification_questions");
        if (questionsElement != null && questionsElement.isJsonArray()) {
            questionsElement.getAsJsonArray().forEach(element -> questions.add(element.getAsString()));
        }
        return TranslationResult.clarificationNeeded(questions);
    }
}
