/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import java.util.Map;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.Ran;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentDomain;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.Transport;

/**
 * Maps an intent's {@code domain} leaf (an {@code identityref}, bound to the identity's
 * singleton {@code VALUE} instance rather than a {@code Class} object in this yangtools
 * version) to the module-qualified string key ({@code IntentCompiler#domain()} /
 * {@code DomainActuator#domain()} use, e.g. {@code "lighty-intent-transport:transport"}).
 *
 * <p>lighty-intent-core depends on lighty-intent-models (which carries the generated identity
 * marker interfaces for every domain module, transport and RAN-CCO included) but never on the
 * lighty-intent-transport / lighty-intent-ran Maven modules themselves; only this small,
 * explicit table needs to grow when a new domain module is added.
 */
final class DomainKeys {

    private static final Map<IntentDomain, String> KEYS_BY_IDENTITY = Map.of(
            Transport.VALUE, "lighty-intent-transport:transport",
            Ran.VALUE, "lighty-intent-ran-cco:ran");

    private DomainKeys() {
        // utility class
    }

    /**
     * @param domain the value of an intent's {@code domain} leaf
     * @return the module-qualified domain key, or {@code null} if {@code domain} is not a
     *     domain identity lighty-intent-core knows about
     */
    static String keyOf(final IntentDomain domain) {
        return KEYS_BY_IDENTITY.get(domain);
    }
}
