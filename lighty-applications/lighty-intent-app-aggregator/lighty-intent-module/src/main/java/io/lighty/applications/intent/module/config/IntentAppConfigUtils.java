/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lighty.applications.util.ModulesConfig;
import io.lighty.core.controller.impl.config.ConfigurationException;
import io.lighty.core.controller.impl.config.ControllerConfiguration;
import io.lighty.core.controller.impl.util.ControllerConfigUtils;
import io.lighty.gnmi.southbound.lightymodule.util.GnmiConfigUtils;
import io.lighty.modules.northbound.restconf.community.impl.config.RestConfConfiguration;
import io.lighty.modules.northbound.restconf.community.impl.util.RestConfConfigUtils;
import io.lighty.server.config.LightyServerConfig;
import io.lighty.server.util.LightyServerConfigUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.opendaylight.yang.svc.v1.urn.lighty.intent.rev260926.YangModuleInfoImpl;
import org.opendaylight.yangtools.binding.meta.YangModuleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link IntentAppConfiguration} from a lighty.io JSON config file, or builds one from
 * every sub-config's own defaults, following the same pattern as
 * {@code io.lighty.applications.rnc.module.config.RncLightyModuleConfigUtils}.
 */
public final class IntentAppConfigUtils {

    private static final Logger LOG = LoggerFactory.getLogger(IntentAppConfigUtils.class);

    private static final String GNMI_TARGET_ELEMENT_NAME = "gnmi-target";
    private static final String NL_SLM_ELEMENT_NAME = "nl-slm";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IntentAppConfigUtils() {
        throw new UnsupportedOperationException();
    }

    public static IntentAppConfiguration loadConfigFromFile(final Path configPath) throws ConfigurationException {
        LOG.info("Loading intent lighty.io application configuration from file {} ...", configPath);
        final ControllerConfiguration controllerConfig;
        final LightyServerConfig serverConfig;
        final RestConfConfiguration restconfConfig;
        final GnmiTargetConfig gnmiTargetConfig;
        final NlSlmConfig nlSlmConfig;
        final ModulesConfig moduleConfig;
        try {
            LOG.debug("Loading lighty.io controller module configuration from file...");
            controllerConfig = ControllerConfigUtils.getConfiguration(Files.newInputStream(configPath));
            addDefaultAppModels(controllerConfig);
            LOG.debug("lighty.io controller module configuration from file loaded!");

            LOG.debug("Loading lighty.io RESTCONF module configuration from file...");
            restconfConfig = RestConfConfigUtils.getRestConfConfiguration(Files.newInputStream(configPath));
            LOG.debug("lighty.io RESTCONF module configuration from file loaded!");

            LOG.debug("Loading lighty.io Jetty server module configuration from file...");
            serverConfig = LightyServerConfigUtils.getServerConfiguration(Files.newInputStream(configPath));
            LOG.debug("lighty.io Jetty server module configuration from file loaded!");

            LOG.debug("Loading gNMI target configuration from file...");
            gnmiTargetConfig = readOrDefault(configPath, GNMI_TARGET_ELEMENT_NAME, GnmiTargetConfig.class,
                    GnmiTargetConfig::new);
            LOG.debug("gNMI target configuration from file loaded!");

            LOG.debug("Loading NL SLM sidecar configuration from file...");
            nlSlmConfig = readOrDefault(configPath, NL_SLM_ELEMENT_NAME, NlSlmConfig.class, NlSlmConfig::new);
            LOG.debug("NL SLM sidecar configuration from file loaded!");

            LOG.debug("Loading lighty.io app module configuration from file...");
            moduleConfig = ModulesConfig.getModulesConfig(Files.newInputStream(configPath));
            LOG.debug("lighty.io app module configuration from file loaded!");
        } catch (IOException e) {
            throw new ConfigurationException("Exception thrown while loading configuration!", e);
        }
        return new IntentAppConfiguration(controllerConfig, serverConfig, restconfConfig, gnmiTargetConfig,
                nlSlmConfig, moduleConfig);
    }

    public static IntentAppConfiguration loadDefaultConfig() throws ConfigurationException {
        LOG.info("Loading default intent lighty.io application configuration ...");
        final Set<YangModuleInfo> modelPaths = new HashSet<>();
        defaultModels(modelPaths);

        LOG.debug("Loading default lighty.io controller module configuration...");
        final ControllerConfiguration controllerConfig = ControllerConfigUtils
                .getDefaultSingleNodeConfiguration(modelPaths);
        LOG.debug("Default lighty.io controller module configuration loaded!");

        LOG.debug("Loading default lighty.io RESTCONF module configuration...");
        final RestConfConfiguration restconfConfig = RestConfConfigUtils.getDefaultRestConfConfiguration();
        LOG.debug("Default lighty.io RESTCONF module configuration loaded!");

        LOG.debug("Loading default lighty.io Jetty server module configuration...");
        final LightyServerConfig serverConfig = LightyServerConfigUtils.getDefaultLightyServerConfig();
        LOG.debug("Default lighty.io Jetty server module configuration loaded!");

        LOG.debug("Loading default gNMI target configuration...");
        final GnmiTargetConfig gnmiTargetConfig = new GnmiTargetConfig();
        LOG.debug("Default gNMI target configuration loaded!");

        LOG.debug("Loading default NL SLM sidecar configuration...");
        final NlSlmConfig nlSlmConfig = new NlSlmConfig();
        LOG.debug("Default NL SLM sidecar configuration loaded!");

        LOG.debug("Loading default lighty.io app module configuration...");
        final ModulesConfig moduleConfig = ModulesConfig.getDefaultModulesConfig();
        LOG.debug("Default lighty.io app module configuration loaded!");

        return new IntentAppConfiguration(controllerConfig, serverConfig, restconfConfig, gnmiTargetConfig,
                nlSlmConfig, moduleConfig);
    }

    private static <T> T readOrDefault(final Path configPath, final String elementName, final Class<T> type,
            final java.util.function.Supplier<T> defaultSupplier) throws IOException {
        final JsonNode configNode;
        try (var inputStream = Files.newInputStream(configPath)) {
            configNode = MAPPER.readTree(inputStream);
        }
        if (!configNode.has(elementName)) {
            LOG.warn("Json config does not contain {} element. Using defaults.", elementName);
            return defaultSupplier.get();
        }
        return MAPPER.treeToValue(configNode.path(elementName), type);
    }

    private static void addDefaultAppModels(final ControllerConfiguration controllerConfig) {
        LOG.debug("Adding minimal needed yang models if they are not present...");
        final Set<YangModuleInfo> modelPaths = new HashSet<>(controllerConfig.getSchemaServiceConfig().getModels());
        defaultModels(modelPaths);
        controllerConfig.getSchemaServiceConfig().setModels(Collections.unmodifiableSet(modelPaths));
    }

    private static void defaultModels(final Set<YangModuleInfo> modelPaths) {
        modelPaths.addAll(RestConfConfigUtils.YANG_MODELS);
        modelPaths.addAll(GnmiConfigUtils.YANG_MODELS);
        modelPaths.add(YangModuleInfoImpl.getInstance());
        modelPaths.add(org.opendaylight.yang.svc.v1.urn.lighty.intent.transport.rev260926
                .YangModuleInfoImpl.getInstance());
        modelPaths.add(org.opendaylight.yang.svc.v1.urn.lighty.intent.ran.cco.rev260926
                .YangModuleInfoImpl.getInstance());
    }
}
