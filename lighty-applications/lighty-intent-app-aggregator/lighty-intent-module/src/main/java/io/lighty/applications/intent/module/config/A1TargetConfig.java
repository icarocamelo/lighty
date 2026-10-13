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
 * Base URL of the Near-RT RIC's A1 mediator that {@code RanCcoActuator} actuates RAN
 * Coverage/Capacity-Optimization intents against (see {@code IntentAppModule}), consumed by
 * {@code HttpA1PolicyClient}.
 *
 * <p>Routing a multi-cell intent's compiled plan to several Near-RT RICs is out of scope for this
 * application, exactly as multi-device routing is for {@code lighty-intent-transport}'s
 * {@code GnmiTransportActuator} (see its Javadoc); this configuration only ever describes one
 * RIC.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class A1TargetConfig {

    private String baseUrl = "http://127.0.0.1:9990";

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
        if (!(obj instanceof A1TargetConfig)) {
            return false;
        }
        return Objects.equals(baseUrl, ((A1TargetConfig) obj).baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl);
    }
}
