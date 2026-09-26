/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module;

import io.lighty.applications.intent.module.config.GnmiTargetConfig;
import io.lighty.applications.intent.module.config.IntentAppConfiguration;
import io.lighty.applications.intent.module.config.NlSlmConfig;
import io.lighty.applications.intent.module.exception.IntentAppStartException;
import io.lighty.core.controller.api.LightyController;
import io.lighty.core.controller.api.LightyModule;
import io.lighty.core.controller.api.LightyServices;
import io.lighty.core.controller.impl.LightyControllerBuilder;
import io.lighty.core.controller.impl.config.ConfigurationException;
import io.lighty.core.controller.impl.config.ControllerConfiguration;
import io.lighty.modules.gnmi.connector.configuration.SecurityFactory;
import io.lighty.modules.gnmi.connector.configuration.SessionConfiguration;
import io.lighty.modules.gnmi.connector.gnmi.session.impl.GnmiSessionFactoryImpl;
import io.lighty.modules.gnmi.connector.session.SessionManagerFactoryImpl;
import io.lighty.modules.gnmi.connector.session.api.SessionManager;
import io.lighty.modules.gnmi.connector.session.api.SessionProvider;
import io.lighty.modules.intent.api.AssuranceSource;
import io.lighty.modules.intent.api.ConflictRule;
import io.lighty.modules.intent.api.DomainActuator;
import io.lighty.modules.intent.api.IntentCompiler;
import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.core.IntentLightyModule;
import io.lighty.modules.intent.nlclient.FallbackNlTranslator;
import io.lighty.modules.intent.nlclient.RuleBasedNlTranslator;
import io.lighty.modules.intent.nlclient.SidecarNlTranslator;
import io.lighty.modules.intent.ran.RanCcoCompiler;
import io.lighty.modules.intent.transport.GnmiTransportActuator;
import io.lighty.modules.intent.transport.TransportQosCompiler;
import io.lighty.modules.northbound.restconf.community.impl.CommunityRestConf;
import io.lighty.modules.northbound.restconf.community.impl.CommunityRestConfBuilder;
import io.lighty.modules.northbound.restconf.community.impl.config.RestConfConfiguration;
import io.lighty.modules.northbound.restconf.community.impl.util.RestConfConfigUtils;
import io.lighty.server.LightyJettyServerProvider;
import io.lighty.server.config.LightyServerConfig;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code AbstractLightyModule} wiring for lighty.io's intent-driven-management (IDM)
 * application: assembles a {@link LightyController}, a {@link CommunityRestConf} RESTCONF
 * northbound, one already-connected gNMI {@link SessionProvider} for the transport domain's
 * {@link GnmiTransportActuator}, and lighty-intent-core's {@link IntentLightyModule}, wired up
 * with:
 *
 * <ul>
 *   <li>{@link TransportQosCompiler} + {@link GnmiTransportActuator} for the
 *       {@code lighty-intent-transport:transport} domain (compiled <em>and</em> actuated);</li>
 *   <li>{@link RanCcoCompiler} for the {@code lighty-intent-ran-cco:ran} domain (compiled for
 *       {@code dry-run} only - no {@code DomainActuator} is registered for it, so
 *       {@code approve-intent} on a RAN CCO intent fails with "no actuator registered", exactly
 *       as documented as expected behaviour by lighty-intent-ran's own README);</li>
 *   <li>a {@link FallbackNlTranslator} chaining {@link SidecarNlTranslator} (the
 *       {@code lighty-intent-slm} sidecar) in front of {@link RuleBasedNlTranslator}, backing
 *       the {@code translate-nl} RPC.</li>
 * </ul>
 *
 * <p>Named {@code IntentAppModule} (rather than reusing {@code IntentLightyModule}) purely to
 * avoid a name clash with lighty-intent-core's own {@code io.lighty.modules.intent.core.IntentLightyModule}
 * class, which this class wires up as one of its components - mirroring how
 * {@code RncLightyModule} wires up {@code CommunityRestConf}/{@code NetconfSBPlugin}/etc.
 * without being named after any one of them.
 *
 * <p>Reduced scope versus {@code RncLightyModule}: no AAA, no OpenApi endpoint, and no NETCONF
 * southbound/call-home - this application's only southbound is the single configured gNMI
 * target device, and its only northbound is RESTCONF.
 */
public class IntentAppModule {

    private static final Logger LOG = LoggerFactory.getLogger(IntentAppModule.class);
    private static final TimeUnit DEFAULT_LIGHTY_MODULE_TIME_UNIT = TimeUnit.SECONDS;

    private final IntentAppConfiguration intentAppConfig;
    private final long lightyModuleTimeout;

    private LightyController lightyController;
    private CommunityRestConf lightyRestconf;
    private LightyJettyServerProvider jettyServerBuilder;
    private SessionManager gnmiSessionManager;
    private SessionProvider gnmiSessionProvider;
    private IntentLightyModule intentLightyModule;

    public IntentAppModule(final IntentAppConfiguration intentAppConfig) {
        LOG.info("Creating instance of intent lighty.io module...");
        this.intentAppConfig = intentAppConfig;
        this.lightyModuleTimeout = intentAppConfig.getModuleConfig().getModuleTimeoutSeconds();
        LOG.info("Instance of intent lighty.io module created!");
    }

    public boolean initModules() {
        LOG.info("Initializing intent lighty.io module...");
        try {
            this.lightyController = initController(intentAppConfig.getControllerConfig());
            startAndWaitLightyModule(this.lightyController);

            this.lightyRestconf = initRestconf(intentAppConfig.getRestconfConfig(), intentAppConfig.getServerConfig(),
                    this.lightyController.getServices());
            startAndWaitLightyModule(this.lightyRestconf);

            this.gnmiSessionProvider = connectGnmiTarget(intentAppConfig.getGnmiTargetConfig());

            this.intentLightyModule = initIntentModule(this.lightyController.getServices(), this.gnmiSessionProvider,
                    intentAppConfig.getNlSlmConfig());
            startAndWaitLightyModule(this.intentLightyModule);

            lightyRestconf.startServer();
        } catch (IntentAppStartException e) {
            LOG.error("Unable to initialize and start intent lighty.io module!", e);
            return false;
        }
        LOG.info("Intent lighty.io module initialized successfully!");
        return true;
    }

    private LightyController initController(final ControllerConfiguration config) throws IntentAppStartException {
        try {
            return new LightyControllerBuilder().from(config).build();
        } catch (ConfigurationException e) {
            throw new IntentAppStartException("Unable to initialize lighty.io controller module!", e);
        }
    }

    private CommunityRestConf initRestconf(final RestConfConfiguration rcConfig,
            final LightyServerConfig serverConfig, final LightyServices services) {
        final var restConfConfiguration = RestConfConfigUtils.getRestConfConfiguration(rcConfig, services);
        final var inetSocketAddress = new InetSocketAddress(rcConfig.getInetAddress(), rcConfig.getHttpPort());

        jettyServerBuilder = new LightyJettyServerProvider(serverConfig, inetSocketAddress);

        return CommunityRestConfBuilder.from(restConfConfiguration)
                .withLightyServer(jettyServerBuilder)
                .build();
    }

    /**
     * Builds the (lazily-connecting) gNMI {@link SessionProvider} for the single target device
     * configured in {@code gnmiTargetConfig}. Building the underlying gRPC channel never blocks
     * or fails here even if the target is currently unreachable - the channel only actually
     * connects on the first RPC {@link GnmiTransportActuator} sends it (at
     * {@code approve-intent} time for a transport-domain intent); see the module Javadoc.
     */
    private SessionProvider connectGnmiTarget(final GnmiTargetConfig gnmiTargetConfig) {
        LOG.info("Preparing gNMI session to target {}:{} (plaintext={})", gnmiTargetConfig.getHost(),
                gnmiTargetConfig.getPort(), gnmiTargetConfig.isUsePlainText());
        this.gnmiSessionManager = new SessionManagerFactoryImpl(new GnmiSessionFactoryImpl())
                .createSessionManager(SecurityFactory.createInsecureGnmiSecurity());
        final InetSocketAddress targetAddress = new InetSocketAddress(gnmiTargetConfig.getHost(),
                gnmiTargetConfig.getPort());
        final SessionConfiguration sessionConfiguration = new SessionConfiguration(targetAddress,
                gnmiTargetConfig.isUsePlainText(), gnmiTargetConfig.getUsername(), gnmiTargetConfig.getPassword());
        return gnmiSessionManager.createSession(sessionConfiguration);
    }

    private IntentLightyModule initIntentModule(final LightyServices services,
            final SessionProvider sessionProvider, final NlSlmConfig nlSlmConfig) {
        final Map<String, IntentCompiler> compilersByDomain = new HashMap<>();
        compilersByDomain.put(TransportQosCompiler.DOMAIN, new TransportQosCompiler());
        compilersByDomain.put(RanCcoCompiler.DOMAIN, new RanCcoCompiler());

        final Map<String, DomainActuator> actuatorsByDomain = new HashMap<>();
        actuatorsByDomain.put(GnmiTransportActuator.DOMAIN, new GnmiTransportActuator(sessionProvider));
        // No DomainActuator is registered for RanCcoCompiler.DOMAIN in this version: dry-run
        // works, approve-intent on a "ran" domain intent fails with a clear "no actuator
        // registered" error, exactly as documented as expected behaviour by lighty-intent-ran.

        final Map<String, AssuranceSource> assuranceSourcesByDomain = Map.of();
        final List<ConflictRule> conflictRules = List.of();

        final NlTranslator nlTranslator = new FallbackNlTranslator(
                new SidecarNlTranslator(nlSlmConfig.getBaseUrl()), new RuleBasedNlTranslator());

        return new IntentLightyModule(services.getBindingDataBroker(), services.getRpcProviderService(),
                compilersByDomain, actuatorsByDomain, assuranceSourcesByDomain, conflictRules, nlTranslator);
    }

    private void startAndWaitLightyModule(final LightyModule lightyModule) throws IntentAppStartException {
        try {
            LOG.info("Initializing lighty.io module ({})...", lightyModule.getClass());
            boolean startSuccess = lightyModule.start().get(lightyModuleTimeout, DEFAULT_LIGHTY_MODULE_TIME_UNIT);
            if (startSuccess) {
                LOG.info("lighty.io module ({}) initialized successfully!", lightyModule.getClass());
            } else {
                throw new IntentAppStartException(
                        String.format("Unable to initialize lighty.io module (%s)!", lightyModule.getClass()));
            }
        } catch (TimeoutException | ExecutionException e) {
            throw new IntentAppStartException(
                    String.format("Exception was thrown during initialization of lighty.io module (%s)!",
                            lightyModule.getClass()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntentAppStartException(
                    String.format("Exception was thrown during initialization of lighty.io module (%s)!",
                            lightyModule.getClass()), e);
        }
    }

    public boolean close() {
        LOG.info("Stopping intent lighty.io application...");
        boolean success = true;
        if (this.intentLightyModule != null) {
            success &= intentLightyModule.shutdown(lightyModuleTimeout, DEFAULT_LIGHTY_MODULE_TIME_UNIT);
        }
        if (this.gnmiSessionProvider != null) {
            try {
                gnmiSessionProvider.close();
            } catch (Exception e) {
                LOG.warn("Failed to close gNMI session", e);
                success = false;
            }
        }
        if (this.lightyRestconf != null) {
            success &= lightyRestconf.shutdown(lightyModuleTimeout, DEFAULT_LIGHTY_MODULE_TIME_UNIT);
        }
        if (this.lightyController != null) {
            success &= lightyController.shutdown(lightyModuleTimeout, DEFAULT_LIGHTY_MODULE_TIME_UNIT);
        }
        if (success) {
            LOG.info("Intent lighty.io module stopped successfully!");
            return true;
        } else {
            LOG.error("Some components of intent lighty.io module were not stopped successfully!");
            return false;
        }
    }
}
