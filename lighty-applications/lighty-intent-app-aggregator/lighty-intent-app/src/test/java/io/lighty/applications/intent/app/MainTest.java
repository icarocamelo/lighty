/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.lighty.applications.intent.module.IntentAppModule;
import org.testng.annotations.Test;

public class MainTest {

    @Test
    public void testStartWithDefaultConfiguration() {
        final Main app = spy(new Main());
        final IntentAppModule module = mock(IntentAppModule.class);
        doReturn(true).when(module).initModules();
        doReturn(true).when(module).close();
        doReturn(module).when(app).createIntentAppModule(any());
        app.start(new String[] {});
    }

    @Test
    public void testStartWithConfigFileNoSuchFile() {
        final Main app = spy(new Main());
        app.start(new String[] {"-c", "no_such_config.json"});
        verify(app, never()).createIntentAppModule(any());
    }
}
