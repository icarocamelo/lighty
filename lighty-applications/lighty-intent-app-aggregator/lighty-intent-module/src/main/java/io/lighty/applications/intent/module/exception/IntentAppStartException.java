/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.applications.intent.module.exception;

public class IntentAppStartException extends Exception {

    private static final long serialVersionUID = 1L;

    public IntentAppStartException(final String message) {
        super(message);
    }

    public IntentAppStartException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
