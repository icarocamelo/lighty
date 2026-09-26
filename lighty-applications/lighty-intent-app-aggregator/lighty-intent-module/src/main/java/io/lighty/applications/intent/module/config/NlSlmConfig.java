/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;

/**
 * Configuration of the {@code translate-nl} RPC's backing NL-to-intent SLM sidecar
 * ({@code lighty-intent-slm}), consumed by {@code SidecarNlTranslator}
 * (see {@code io.lighty.modules.intent.nlclient}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NlSlmConfig {

    private String baseUrl = "http://localhost:8000";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof NlSlmConfig)) {
            return false;
        }
        return Objects.equals(baseUrl, ((NlSlmConfig) obj).baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl);
    }
}
