/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.transport;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import gnmi.Gnmi;
import io.lighty.modules.gnmi.connector.gnmi.request.RequestBuilder;
import io.lighty.modules.gnmi.connector.session.api.SessionProvider;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.DomainActuator;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuates {@link TransportQosCompiler} plans by writing the {@code
 * lighty-intent-transport:transport-qos-config} container to a device over gNMI.
 *
 * <p><strong>Scope note:</strong> this version actuates against a single, already-connected
 * {@link SessionProvider} passed at construction time. Routing a multi-device intent's
 * compiled plan to several devices/targets (e.g. one gNMI session per {@code
 * expectation-object-instance}) is out of scope for this version; a future revision should
 * have lighty-intent-core (or this actuator) resolve the target device(s) per intent and
 * either hold a {@code Map<String, SessionProvider>} or accept the session as a parameter of
 * {@link #apply(String, CompiledPlan)}.
 */
public final class GnmiTransportActuator implements DomainActuator {

    /**
     * The domain identity this actuator handles, matching {@link TransportQosCompiler#DOMAIN}.
     */
    public static final String DOMAIN = TransportQosCompiler.DOMAIN;

    private static final Logger LOG = LoggerFactory.getLogger(GnmiTransportActuator.class);

    private static final String TRANSPORT_QOS_CONFIG_ELEM = "lighty-intent-transport:transport-qos-config";

    private final SessionProvider sessionProvider;

    public GnmiTransportActuator(final SessionProvider sessionProvider) {
        this.sessionProvider = Objects.requireNonNull(sessionProvider, "sessionProvider");
    }

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public ListenableFuture<Void> apply(final String intentId, final CompiledPlan plan) {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(plan, "plan");
        if (!plan.isFeasible()) {
            throw new IllegalArgumentException(
                    "Cannot apply an infeasible plan for intent " + intentId + ": " + plan.getConflicts());
        }
        LOG.info("Applying transport-qos-config for intent {}", intentId);
        final Gnmi.SetRequest setRequest = RequestBuilder.buildSetRequest(
                transportQosConfigPath(), plan.getRenderedConfigJson(), RequestBuilder.Type.REPLACE);
        return toVoidFuture(sessionProvider.getGnmiSession().set(setRequest));
    }

    @Override
    public ListenableFuture<Void> remove(final String intentId) {
        Objects.requireNonNull(intentId, "intentId");
        LOG.info("Removing transport-qos-config for intent {}", intentId);
        final Gnmi.SetRequest setRequest = RequestBuilder.buildSetRequest(
                transportQosConfigPath(), null, RequestBuilder.Type.DELETE);
        return toVoidFuture(sessionProvider.getGnmiSession().set(setRequest));
    }

    private static ListenableFuture<Void> toVoidFuture(final ListenableFuture<Gnmi.SetResponse> setResponseFuture) {
        return Futures.transform(setResponseFuture, response -> null, MoreExecutors.directExecutor());
    }

    private static Gnmi.Path transportQosConfigPath() {
        return Gnmi.Path.newBuilder()
                .addElem(Gnmi.PathElem.newBuilder().setName(TRANSPORT_QOS_CONFIG_ELEM).build())
                .build();
    }
}
