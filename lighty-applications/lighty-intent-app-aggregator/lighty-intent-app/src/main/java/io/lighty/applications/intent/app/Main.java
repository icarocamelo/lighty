/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.app;

import com.beust.jcommander.JCommander;
import com.google.common.base.Stopwatch;
import io.lighty.applications.intent.module.IntentAppModule;
import io.lighty.applications.intent.module.config.IntentAppConfigUtils;
import io.lighty.applications.intent.module.config.IntentAppConfiguration;
import io.lighty.core.controller.impl.config.ConfigurationException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for lighty.io's intent-driven-management application: loads
 * {@link IntentAppConfiguration} (from {@code --config-path}, or its own defaults) and starts
 * {@link IntentAppModule}, mirroring {@code io.lighty.applications.rnc.app.Main}.
 */
public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    // Using args is safe as we need only a configuration file location here
    @SuppressWarnings("squid:S4823")
    public static void main(final String[] args) {
        Main app = new Main();
        app.start(args);
    }

    public void start(final String[] args) {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        LOG.info(" _ __     __             _   _       __            __ ");
        LOG.info("(_)/ /_  / /____   ____  / /_(_)     / /____ ____  / /_");
        LOG.info("/ / __ \\/ __/ -_) / __/ / __/ / _ \\/ __/ -_) _ \\/ __/");
        LOG.info("/_/_/ /_/\\__/\\__/  \\__/  \\__/_/_//_/\\__/\\__/_//_/\\__/ ");
        LOG.info("Starting lighty.io intent-driven-management application...");

        final IntentAppConfiguration intentAppConfig;
        final Arguments arguments = new Arguments();
        JCommander.newBuilder()
                .addObject(arguments)
                .build()
                .parse(args);

        try {
            if (arguments.getConfigPath() != null) {
                final Path configPath = Paths.get(arguments.getConfigPath());
                intentAppConfig = IntentAppConfigUtils.loadConfigFromFile(configPath);
            } else {
                intentAppConfig = IntentAppConfigUtils.loadDefaultConfig();
            }
        } catch (ConfigurationException e) {
            LOG.error("Unable to load configuration for the intent lighty.io application!", e);
            return;
        }

        final IntentAppModule intentAppModule = createIntentAppModule(intentAppConfig);
        if (intentAppModule.initModules()) {
            LOG.info("Registering ShutdownHook to gracefully shutdown application");
            Runtime.getRuntime().addShutdownHook(new Thread(intentAppModule::close));
            LOG.info("Intent lighty.io application started in {}", stopwatch.stop());
        } else {
            LOG.error("Failed to initialize the intent lighty.io application. Closing application.");
            intentAppModule.close();
        }
    }

    public IntentAppModule createIntentAppModule(final IntentAppConfiguration intentAppConfig) {
        return new IntentAppModule(intentAppConfig);
    }
}
