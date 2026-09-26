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
 * Connection details for the single gNMI target device that {@code GnmiTransportActuator}
 * actuates transport-domain intents against (see {@code IntentAppModule}).
 *
 * <p>Routing a multi-device intent's compiled plan to several gNMI targets is out of scope for
 * this application, exactly as it is for {@code lighty-intent-transport}'s own
 * {@code GnmiTransportActuator} (see its Javadoc); this configuration only ever describes one
 * target.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GnmiTargetConfig {

    private String host = "127.0.0.1";
    private int port = 10161;
    private boolean usePlainText = true;
    private String username;
    private String password;

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public boolean isUsePlainText() {
        return usePlainText;
    }

    public void setUsePlainText(final boolean usePlainText) {
        this.usePlainText = usePlainText;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(final String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GnmiTargetConfig)) {
            return false;
        }
        final GnmiTargetConfig that = (GnmiTargetConfig) obj;
        return port == that.port
                && usePlainText == that.usePlainText
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, usePlainText, username, password);
    }
}
