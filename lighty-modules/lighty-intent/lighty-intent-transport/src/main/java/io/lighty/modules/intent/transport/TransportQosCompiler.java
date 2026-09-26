/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.transport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.lighty.modules.intent.api.CompiledPlan;
import io.lighty.modules.intent.api.IntentCompiler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.expectation.ExpectationTarget;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent.Expectation;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MaxLatency;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MaxPacketLoss;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.MinBandwidth;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.transport.rev260926.TrafficIsolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles transport-domain ({@code lighty-intent-transport:transport}) intents into the
 * {@code lighty-intent-transport:transport-qos-config} container.
 *
 * <p>For every {@code expectation-target} of the intent, this walks the {@code
 * expectation-object-type} identityref (one of {@code min-bandwidth}, {@code max-latency},
 * {@code max-packet-loss} or {@code traffic-isolation}, all defined in {@code
 * lighty-intent-transport.yang}) and groups the results by {@code
 * expectation-object-instance}:
 *
 * <ul>
 *   <li>{@code min-bandwidth} / {@code max-latency} / {@code max-packet-loss} targets that
 *       share an {@code expectation-object-instance} are merged into one {@code qos-policy}
 *       list entry, keyed by that instance (used verbatim as the {@code qos-class}).</li>
 *   <li>Each {@code traffic-isolation} target becomes one {@code network-instance} list
 *       entry, named after the target's desired value and applied to the interface named by
 *       the {@code expectation-object-instance}.</li>
 * </ul>
 *
 * <p>Conflict detection is intentionally narrow for this first version: two intents in
 * {@code ACTIVE}, {@code AWAITING_APPROVAL} or {@code DEPLOYING} state that both request
 * {@code traffic-isolation} for the same {@code expectation-object-instance} but name
 * different {@code network-instance} values conflict, since a single interface cannot be a
 * member of two different network-instances at once. ACL policies are not populated by this
 * version (the {@code acl-policy} list from {@code lighty-intent-transport.yang} is left
 * empty); see the module README.
 */
public final class TransportQosCompiler implements IntentCompiler {

    /**
     * The domain identity this compiler handles, matching {@code lighty-intent-transport:transport}.
     */
    public static final String DOMAIN = "lighty-intent-transport:transport";

    private static final Logger LOG = LoggerFactory.getLogger(TransportQosCompiler.class);

    private static final Set<IntentLifecycleState> CONFLICT_CHECK_STATES = Set.of(
            IntentLifecycleState.ACTIVE,
            IntentLifecycleState.AWAITINGAPPROVAL,
            IntentLifecycleState.DEPLOYING);

    private static final String QOS_POLICY = "qos-policy";
    private static final String QOS_CLASS = "qos-class";
    private static final String MIN_BANDWIDTH_MBPS = "min-bandwidth-mbps";
    private static final String MAX_LATENCY_MS = "max-latency-ms";
    private static final String MAX_PACKET_LOSS_PERCENT = "max-packet-loss-percent";
    private static final String APPLIED_INTERFACE = "applied-interface";
    private static final String NETWORK_INSTANCE = "network-instance";
    private static final String NAME = "name";
    private static final String INTERFACE = "interface";

    private final Gson gson = new Gson();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public CompiledPlan compile(final Intent intent, final Collection<Intent> activeIntents) {
        Objects.requireNonNull(intent, "intent");
        final Collection<Intent> others = activeIntents == null ? List.of() : activeIntents;

        final Map<String, QosPolicyAccumulator> qosPolicyByInstance = new LinkedHashMap<>();
        final Map<String, String> desiredNetworkInstanceByInstance = new LinkedHashMap<>();

        for (final Expectation expectation : valuesOrEmpty(intent.getExpectation())) {
            for (final ExpectationTarget target : valuesOrEmpty(expectation.getExpectationTarget())) {
                accumulateTarget(target, qosPolicyByInstance, desiredNetworkInstanceByInstance);
            }
        }

        final List<String> conflicts = findTrafficIsolationConflicts(intent, desiredNetworkInstanceByInstance,
                others);
        if (!conflicts.isEmpty()) {
            LOG.info("Intent {} is infeasible for domain {}: {}", intent.getIntentId(), DOMAIN, conflicts);
            return CompiledPlan.infeasible(DOMAIN, conflicts);
        }

        final String renderedConfigJson = gson.toJson(
                renderTransportQosConfig(qosPolicyByInstance, desiredNetworkInstanceByInstance));
        return CompiledPlan.feasible(DOMAIN, renderedConfigJson);
    }

    private static void accumulateTarget(final ExpectationTarget target,
            final Map<String, QosPolicyAccumulator> qosPolicyByInstance,
            final Map<String, String> desiredNetworkInstanceByInstance) {
        final String objectInstance = target.getExpectationObjectInstance();
        if (objectInstance == null || target.getExpectationObjectType() == null) {
            return;
        }
        final Class<?> objectType = target.getExpectationObjectType().implementedInterface();
        final String value = firstValue(target.getTargetValueRange());
        if (value == null) {
            return;
        }

        if (objectType == MinBandwidth.class) {
            qosPolicyByInstance.computeIfAbsent(objectInstance, QosPolicyAccumulator::new)
                    .minBandwidthMbps = parseLong(value);
        } else if (objectType == MaxLatency.class) {
            qosPolicyByInstance.computeIfAbsent(objectInstance, QosPolicyAccumulator::new)
                    .maxLatencyMs = parseLong(value);
        } else if (objectType == MaxPacketLoss.class) {
            qosPolicyByInstance.computeIfAbsent(objectInstance, QosPolicyAccumulator::new)
                    .maxPacketLossPercent = parseDecimal(value);
        } else if (objectType == TrafficIsolation.class) {
            desiredNetworkInstanceByInstance.put(objectInstance, value);
        } else {
            LOG.debug("Ignoring expectation-target with unsupported expectation-object-type {} on instance {}",
                    objectType, objectInstance);
        }
    }

    private static List<String> findTrafficIsolationConflicts(final Intent intent,
            final Map<String, String> desiredNetworkInstanceByInstance, final Collection<Intent> others) {
        final List<String> conflicts = new ArrayList<>();
        for (final Map.Entry<String, String> entry : desiredNetworkInstanceByInstance.entrySet()) {
            final String objectInstance = entry.getKey();
            final String desiredNetworkInstance = entry.getValue();
            for (final Intent other : others) {
                if (other == null || Objects.equals(other.getIntentId(), intent.getIntentId())
                        || !CONFLICT_CHECK_STATES.contains(other.getLifecycleState())) {
                    continue;
                }
                final String otherNetworkInstance = trafficIsolationTargetValue(other, objectInstance);
                if (otherNetworkInstance != null && !otherNetworkInstance.equals(desiredNetworkInstance)) {
                    conflicts.add(String.format(
                            "traffic-isolation conflict on expectation-object-instance '%s': intent '%s' requests"
                                    + " network-instance '%s', but intent '%s' (state %s) already has"
                                    + " network-instance '%s'",
                            objectInstance, intent.getIntentId(), desiredNetworkInstance, other.getIntentId(),
                            other.getLifecycleState(), otherNetworkInstance));
                }
            }
        }
        return conflicts;
    }

    private static String trafficIsolationTargetValue(final Intent intent, final String objectInstance) {
        for (final Expectation expectation : valuesOrEmpty(intent.getExpectation())) {
            for (final ExpectationTarget target : valuesOrEmpty(expectation.getExpectationTarget())) {
                if (target.getExpectationObjectType() == null
                        || target.getExpectationObjectType().implementedInterface() != TrafficIsolation.class
                        || !objectInstance.equals(target.getExpectationObjectInstance())) {
                    continue;
                }
                final String value = firstValue(target.getTargetValueRange());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static JsonObject renderTransportQosConfig(final Map<String, QosPolicyAccumulator> qosPolicyByInstance,
            final Map<String, String> desiredNetworkInstanceByInstance) {
        final JsonObject transportQosConfig = new JsonObject();
        if (!qosPolicyByInstance.isEmpty()) {
            final JsonArray qosPolicyArray = new JsonArray();
            qosPolicyByInstance.values().forEach(acc -> qosPolicyArray.add(acc.toJson()));
            transportQosConfig.add(QOS_POLICY, qosPolicyArray);
        }
        if (!desiredNetworkInstanceByInstance.isEmpty()) {
            final JsonArray networkInstanceArray = new JsonArray();
            desiredNetworkInstanceByInstance.forEach((objectInstance, networkInstanceName) -> {
                final JsonObject networkInstance = new JsonObject();
                networkInstance.addProperty(NAME, networkInstanceName);
                final JsonArray interfaces = new JsonArray();
                interfaces.add(objectInstance);
                networkInstance.add(INTERFACE, interfaces);
                networkInstanceArray.add(networkInstance);
            });
            transportQosConfig.add(NETWORK_INSTANCE, networkInstanceArray);
        }
        return transportQosConfig;
    }

    private static <K, V> Collection<V> valuesOrEmpty(final Map<K, V> map) {
        return map == null ? List.of() : map.values();
    }

    private static String firstValue(final Set<String> values) {
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }

    private static Long parseLong(final String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (final NumberFormatException e) {
            LOG.warn("Could not parse '{}' as a long value, ignoring", value, e);
            return null;
        }
    }

    private static BigDecimal parseDecimal(final String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (final NumberFormatException e) {
            LOG.warn("Could not parse '{}' as a decimal value, ignoring", value, e);
            return null;
        }
    }

    /**
     * Accumulates the min-bandwidth / max-latency / max-packet-loss targets found for a
     * single {@code expectation-object-instance} into one {@code qos-policy} JSON entry.
     */
    private static final class QosPolicyAccumulator {
        private final String qosClass;
        private Long minBandwidthMbps;
        private Long maxLatencyMs;
        private BigDecimal maxPacketLossPercent;

        QosPolicyAccumulator(final String qosClass) {
            this.qosClass = qosClass;
        }

        JsonObject toJson() {
            final JsonObject qosPolicy = new JsonObject();
            qosPolicy.addProperty(QOS_CLASS, qosClass);
            if (minBandwidthMbps != null) {
                qosPolicy.addProperty(MIN_BANDWIDTH_MBPS, minBandwidthMbps);
            }
            if (maxLatencyMs != null) {
                qosPolicy.addProperty(MAX_LATENCY_MS, maxLatencyMs);
            }
            if (maxPacketLossPercent != null) {
                qosPolicy.addProperty(MAX_PACKET_LOSS_PERCENT, maxPacketLossPercent);
            }
            final JsonArray appliedInterface = new JsonArray();
            appliedInterface.add(qosClass);
            qosPolicy.add(APPLIED_INTERFACE, appliedInterface);
            return qosPolicy;
        }
    }
}
