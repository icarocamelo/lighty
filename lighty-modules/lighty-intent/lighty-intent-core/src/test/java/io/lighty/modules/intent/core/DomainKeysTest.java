/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.ran.cco.rev260926.Ran;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentDomain;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.Transport;

public class DomainKeysTest {

    @Test
    public void knownDomainsResolve() {
        assertEquals("lighty-intent-transport:transport", DomainKeys.keyOf(Transport.VALUE));
        assertEquals("lighty-intent-ran-cco:ran", DomainKeys.keyOf(Ran.VALUE));
    }

    @Test
    public void unknownDomainResolvesToNull() {
        assertNull(DomainKeys.keyOf(IntentDomain.VALUE));
    }
}
