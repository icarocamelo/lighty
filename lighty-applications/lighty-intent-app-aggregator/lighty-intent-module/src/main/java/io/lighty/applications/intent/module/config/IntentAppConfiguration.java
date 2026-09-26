/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module.config;

import io.lighty.applications.util.ModulesConfig;
import io.lighty.core.controller.impl.config.ControllerConfiguration;
import io.lighty.modules.northbound.restconf.community.impl.config.RestConfConfiguration;
import io.lighty.server.config.LightyServerConfig;

/**
 * Immutable holder of every configuration section {@code IntentAppModule} needs to start:
 * the lighty.io controller, RESTCONF northbound, the single gNMI target device that transport
 * intents are actuated against, the NL-to-intent SLM sidecar base URL, and the generic
 * module-timeout section shared with lighty-rnc-app-aggregator's own configuration
 * ({@code io.lighty.applications.util.ModulesConfig}).
 */
public class IntentAppConfiguration {

    private final ControllerConfiguration controllerConfig;
    private final LightyServerConfig serverConfig;
    private final RestConfConfiguration restconfConfig;
    private final GnmiTargetConfig gnmiTargetConfig;
    private final NlSlmConfig nlSlmConfig;
    private final ModulesConfig moduleConfig;

    public IntentAppConfiguration(final ControllerConfiguration controllerConfig,
            final LightyServerConfig serverConfig, final RestConfConfiguration restconfConfig,
            final GnmiTargetConfig gnmiTargetConfig, final NlSlmConfig nlSlmConfig,
            final ModulesConfig moduleConfig) {
        this.controllerConfig = controllerConfig;
        this.serverConfig = serverConfig;
        this.restconfConfig = restconfConfig;
        this.gnmiTargetConfig = gnmiTargetConfig;
        this.nlSlmConfig = nlSlmConfig;
        this.moduleConfig = moduleConfig;
    }

    public ControllerConfiguration getControllerConfig() {
        return controllerConfig;
    }

    public LightyServerConfig getServerConfig() {
        return serverConfig;
    }

    public RestConfConfiguration getRestconfConfig() {
        return restconfConfig;
    }

    public GnmiTargetConfig getGnmiTargetConfig() {
        return gnmiTargetConfig;
    }

    public NlSlmConfig getNlSlmConfig() {
        return nlSlmConfig;
    }

    public ModulesConfig getModuleConfig() {
        return moduleConfig;
    }
}
