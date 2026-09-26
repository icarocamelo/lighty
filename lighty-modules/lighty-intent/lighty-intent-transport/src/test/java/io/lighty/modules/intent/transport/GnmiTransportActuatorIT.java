/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.transport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.io.CharStreams;
import com.google.gson.JsonParser;
import gnmi.Gnmi;
import io.lighty.aaa.util.AAAConfigUtils;
import io.lighty.modules.gnmi.connector.configuration.SecurityFactory;
import io.lighty.modules.gnmi.connector.configuration.SessionConfiguration;
import io.lighty.modules.gnmi.connector.gnmi.session.impl.GnmiSessionFactoryImpl;
import io.lighty.modules.gnmi.connector.security.Security;
import io.lighty.modules.gnmi.connector.session.SessionManagerFactoryImpl;
import io.lighty.modules.gnmi.connector.session.api.SessionManager;
import io.lighty.modules.gnmi.connector.session.api.SessionProvider;
import io.lighty.modules.gnmi.simulatordevice.config.GnmiSimulatorConfiguration;
import io.lighty.modules.gnmi.simulatordevice.impl.SimulatedGnmiDevice;
import io.lighty.modules.gnmi.simulatordevice.utils.GnmiSimulatorConfUtils;
import io.lighty.modules.intent.api.CompiledPlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTargetBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.ExpectationBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MinBandwidth;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.TrafficIsolation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.Transport;
import org.opendaylight.yangtools.binding.util.BindingMap;
import org.skyscreamer.jsonassert.JSONAssert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test that compiles a sample transport-domain intent with {@link
 * TransportQosCompiler}, actuates it over gNMI with {@link GnmiTransportActuator} against an
 * in-process {@link SimulatedGnmiDevice} (mirroring lighty-gnmi-test's {@code
 * SimulatorCrudTest}), then reads the applied configuration back and removes it.
 */
class GnmiTransportActuatorIT {

    private static final Logger LOG = LoggerFactory.getLogger(GnmiTransportActuatorIT.class);

    private static final int TARGET_PORT = 11161;
    private static final String TARGET_HOST = "127.0.0.1";
    private static final String SIMULATOR_CONFIG = "/json/simulator_config.json";
    private static final String SERVER_KEY = "src/test/resources/certs/server-pkcs8.key";
    private static final String SERVER_CERT = "src/test/resources/certs/server.crt";
    private static final String CLIENT_KEY = "/certs/client.key";
    private static final String CLIENT_CERTS = "/certs/client.crt";
    private static final String CA_CERTS = "/certs/ca.crt";
    private static final String PASSPHRASE = "";

    private static final String TRANSPORT_QOS_CONFIG_ELEM = "lighty-intent-transport:transport-qos-config";

    private SimulatedGnmiDevice target;
    private SessionProvider sessionProvider;

    @BeforeEach
    void setUp() throws Exception {
        final GnmiSimulatorConfiguration simulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(getClass().getResourceAsStream(SIMULATOR_CONFIG));
        simulatorConfiguration.setTargetAddress(TARGET_HOST);
        simulatorConfiguration.setTargetPort(TARGET_PORT);
        simulatorConfiguration.setCertKeyPath(SERVER_KEY);
        simulatorConfiguration.setCertPath(SERVER_CERT);

        target = new SimulatedGnmiDevice(simulatorConfiguration);
        target.start();

        final SessionManager sessionManager = createSessionManagerWithCerts();
        final InetSocketAddress targetAddress = new InetSocketAddress(TARGET_HOST, TARGET_PORT);
        sessionProvider = sessionManager.createSession(new SessionConfiguration(targetAddress, false));
    }

    @AfterEach
    void tearDown() throws Exception {
        sessionProvider.close();
        target.stop();
    }

    @Test
    void appliesCompiledPlanThenReadsItBackThenRemovesIt() throws ExecutionException, InterruptedException,
            JSONException {
        final Intent intent = sampleIntent();
        final TransportQosCompiler compiler = new TransportQosCompiler();
        final CompiledPlan plan = compiler.compile(intent, List.of());
        assertTrue(plan.isFeasible(), "Expected sample intent to compile to a feasible plan: "
                + plan.getConflicts());

        final GnmiTransportActuator actuator = new GnmiTransportActuator(sessionProvider);

        LOG.info("Applying plan for intent {}: {}", intent.getIntentId(), plan.getRenderedConfigJson());
        actuator.apply(intent.getIntentId(), plan).get();

        final Gnmi.GetRequest getRequest = transportQosConfigGetRequest();
        final Gnmi.GetResponse getResponse = sessionProvider.getGnmiSession().get(getRequest).get();
        final String rawResponseJson = getResponse.getNotification(0).getUpdate(0).getVal().getJsonIetfVal()
                .toStringUtf8();
        // Get on a top-level, module-qualified container path returns the value wrapped in
        // its own module-qualified name (unlike Set, which takes the bare container
        // contents matching the CompiledPlan's renderedConfigJson) - see GnmiHelper/
        // RequestBuilder and lighty-gnmi-test's SimulatorCrudTest#crudComplexValueTest.
        final String actualConfigJson = JsonParser.parseString(rawResponseJson).getAsJsonObject()
                .getAsJsonObject(TRANSPORT_QOS_CONFIG_ELEM).toString();
        JSONAssert.assertEquals(plan.getRenderedConfigJson(), actualConfigJson, false);

        LOG.info("Removing plan for intent {}", intent.getIntentId());
        actuator.remove(intent.getIntentId()).get();

        assertThrows(ExecutionException.class, () -> sessionProvider.getGnmiSession().get(getRequest).get());
    }

    private static Gnmi.GetRequest transportQosConfigGetRequest() {
        final Gnmi.Path path = Gnmi.Path.newBuilder()
                .addElem(Gnmi.PathElem.newBuilder().setName(TRANSPORT_QOS_CONFIG_ELEM).build())
                .build();
        return Gnmi.GetRequest.newBuilder()
                .addPath(path)
                .setEncoding(Gnmi.Encoding.JSON_IETF)
                .build();
    }

    private static Intent sampleIntent() {
        final ExpectationTarget bandwidthTarget = new ExpectationTargetBuilder()
                .setExpectationObjectType(MinBandwidth.VALUE)
                .setExpectationObjectInstance("eth0")
                .setTargetValueRange(Set.of("100"))
                .build();
        final ExpectationTarget isolationTarget = new ExpectationTargetBuilder()
                .setExpectationObjectType(TrafficIsolation.VALUE)
                .setExpectationObjectInstance("eth0")
                .setTargetValueRange(Set.of("vrf-red"))
                .build();
        final Expectation expectation = new ExpectationBuilder()
                .setExpectationId("exp-1")
                .setExpectationTarget(BindingMap.of(bandwidthTarget, isolationTarget))
                .build();
        return new IntentBuilder()
                .setIntentId("intent-it-1")
                .setDomain(Transport.VALUE)
                .setLifecycleState(IntentLifecycleState.DRAFT)
                .setExpectation(BindingMap.of(expectation))
                .build();
    }

    private static SessionManager createSessionManagerWithCerts() throws Exception {
        final KeyPair keyPair = AAAConfigUtils.decodePrivateKey(new StringReader(readResource(CLIENT_KEY)),
                PASSPHRASE);
        final Security gnmiSecurity = SecurityFactory.createGnmiSecurity(readResource(CA_CERTS),
                readResource(CLIENT_CERTS), keyPair.getPrivate());
        return new SessionManagerFactoryImpl(new GnmiSessionFactoryImpl()).createSessionManager(gnmiSecurity);
    }

    private static String readResource(final String classPath) throws IOException {
        try (InputStream inputStream = GnmiTransportActuatorIT.class.getResourceAsStream(classPath)) {
            return CharStreams.toString(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        }
    }
}
