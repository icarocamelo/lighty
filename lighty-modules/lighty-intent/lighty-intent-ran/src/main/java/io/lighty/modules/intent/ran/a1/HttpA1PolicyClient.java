/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran.a1;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link A1PolicyClient} that talks the O-RAN Alliance A1-P REST interface directly with
 * {@link HttpClient}: {@code PUT}/{@code DELETE}
 * {@code {ricBaseUrl}/A1-P/v2/policytypes/{policyTypeId}/policies/{policyId}}.
 *
 * <p>{@code ricBaseUrl} is a full base URL including scheme, e.g.
 * {@code https://near-rt-ric.example.org:9990} for a real Near-RT RIC's A1 mediator, or
 * {@code http://localhost:PORT} against a loopback test server. This class does not assume a
 * scheme; whoever constructs it decides whether TLS is used.
 */
public final class HttpA1PolicyClient implements A1PolicyClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpA1PolicyClient.class);

    private final String ricBaseUrl;
    private final HttpClient httpClient;

    /**
     * @param ricBaseUrl base URL of the Near-RT RIC's A1 mediator, no trailing slash required
     *     (e.g. {@code https://near-rt-ric.example.org:9990})
     */
    public HttpA1PolicyClient(final String ricBaseUrl) {
        this(ricBaseUrl, HttpClient.newHttpClient());
    }

    /**
     * @param ricBaseUrl base URL of the Near-RT RIC's A1 mediator
     * @param httpClient the {@link HttpClient} to send requests with, e.g. a test double pointed
     *     at a loopback server
     */
    public HttpA1PolicyClient(final String ricBaseUrl, final HttpClient httpClient) {
        this.ricBaseUrl = Objects.requireNonNull(ricBaseUrl, "ricBaseUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public void createPolicy(final String policyTypeId, final String policyId, final String policyJson)
            throws IOException, InterruptedException {
        final HttpRequest request = requestBuilder(policyTypeId, policyId)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(policyJson, StandardCharsets.UTF_8))
            .build();
        send(request, "create");
    }

    @Override
    public void deletePolicy(final String policyTypeId, final String policyId)
            throws IOException, InterruptedException {
        final HttpRequest request = requestBuilder(policyTypeId, policyId)
            .DELETE()
            .build();
        send(request, "delete");
    }

    private HttpRequest.Builder requestBuilder(final String policyTypeId, final String policyId) {
        return HttpRequest.newBuilder(policyUri(policyTypeId, policyId));
    }

    private URI policyUri(final String policyTypeId, final String policyId) {
        final String path = "/A1-P/v2/policytypes/" + encode(policyTypeId) + "/policies/" + encode(policyId);
        return URI.create(stripTrailingSlash(ricBaseUrl) + path);
    }

    private static String stripTrailingSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encode(final String pathSegment) {
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8);
    }

    private void send(final HttpRequest request, final String operation) throws IOException, InterruptedException {
        final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("A1 policy " + operation + " request to " + request.uri()
                + " failed with status " + status + ": " + response.body());
        }
        LOG.debug("A1 policy {} request to {} succeeded with status {}", operation, request.uri(), status);
    }
}
