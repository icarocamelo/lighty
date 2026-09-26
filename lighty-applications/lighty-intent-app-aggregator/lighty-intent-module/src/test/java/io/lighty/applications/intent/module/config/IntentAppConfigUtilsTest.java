/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lighty.core.controller.impl.config.ConfigurationException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class IntentAppConfigUtilsTest {

    @Test
    void testLoadConfigFromFile() throws ConfigurationException, URISyntaxException {
        final var configPath = Paths.get(this.getClass().getResource("/config.json").toURI());
        final var intentAppConfig = IntentAppConfigUtils.loadConfigFromFile(configPath);

        final var restconfConfig = intentAppConfig.getRestconfConfig();
        assertEquals("0.0.0.1", restconfConfig.getInetAddress().getCanonicalHostName());
        assertEquals(8181, restconfConfig.getHttpPort());
        assertEquals("rests", restconfConfig.getRestconfServletContextPath());

        final var serverConfig = intentAppConfig.getServerConfig();
        assertFalse(serverConfig.isUseHttps());
        assertTrue(serverConfig.isUseHttp2());

        final var controllerConfig = intentAppConfig.getControllerConfig();
        assertEquals("./clustered-datastore-restore-test", controllerConfig.getRestoreDirectoryPath());
        assertTrue(controllerConfig.isMetricCaptureEnabled());
        assertNotNull(controllerConfig.getSchemaServiceConfig().getModels());
        // the lighty-intent / lighty-intent-transport / lighty-intent-ran-cco / gNMI topology
        // models are always added on top of whatever the config file itself lists, see
        // IntentAppConfigUtils#addDefaultAppModels
        assertTrue(controllerConfig.getSchemaServiceConfig().getModels().size() > 12);

        final var gnmiTargetConfig = intentAppConfig.getGnmiTargetConfig();
        assertEquals("192.0.2.10", gnmiTargetConfig.getHost());
        assertEquals(12345, gnmiTargetConfig.getPort());
        assertFalse(gnmiTargetConfig.isUsePlainText());
        assertEquals("gnmi-user", gnmiTargetConfig.getUsername());
        assertEquals("gnmi-pass", gnmiTargetConfig.getPassword());

        final var nlSlmConfig = intentAppConfig.getNlSlmConfig();
        assertEquals("http://intent-slm-test:8000", nlSlmConfig.getBaseUrl());

        assertEquals(90, intentAppConfig.getModuleConfig().getModuleTimeoutSeconds());
    }

    @Test
    void testLoadDefaultConfig() throws ConfigurationException {
        final var intentAppConfig = IntentAppConfigUtils.loadDefaultConfig();

        final var restconfConfig = intentAppConfig.getRestconfConfig();
        assertEquals("localhost", restconfConfig.getInetAddress().getCanonicalHostName());
        assertEquals(8888, restconfConfig.getHttpPort());

        final var gnmiTargetConfig = intentAppConfig.getGnmiTargetConfig();
        assertEquals("127.0.0.1", gnmiTargetConfig.getHost());
        assertEquals(10161, gnmiTargetConfig.getPort());
        assertTrue(gnmiTargetConfig.isUsePlainText());

        final var nlSlmConfig = intentAppConfig.getNlSlmConfig();
        assertEquals("http://localhost:8000", nlSlmConfig.getBaseUrl());

        assertEquals(60, intentAppConfig.getModuleConfig().getModuleTimeoutSeconds());

        final var controllerConfig = intentAppConfig.getControllerConfig();
        assertNotNull(controllerConfig.getSchemaServiceConfig().getModels());
        assertTrue(controllerConfig.getSchemaServiceConfig().getModels().size() >= 6);
    }
}
