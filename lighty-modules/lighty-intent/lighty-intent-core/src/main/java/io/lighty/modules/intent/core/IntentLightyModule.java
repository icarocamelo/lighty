/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import io.lighty.core.controller.api.AbstractLightyModule;
import io.lighty.modules.intent.api.AssuranceSource;
import io.lighty.modules.intent.api.ConflictRule;
import io.lighty.modules.intent.api.DomainActuator;
import io.lighty.modules.intent.api.IntentCompiler;
import io.lighty.modules.intent.api.NlTranslator;
import io.lighty.modules.intent.api.ObservedTarget;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.DataTreeIdentifier;
import org.opendaylight.mdsal.binding.api.RpcProviderService;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.DateAndTime;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.FulfilmentStatus;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.Intents;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.intent.report.TargetFulfilment;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.intent.report.TargetFulfilmentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.intent.report.TargetFulfilmentKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * lighty.io module implementing intent-driven management: listens for intents written to the
 * CONFIG datastore, validates/conflict-checks/compiles them, drives their lifecycle, and
 * implements the {@code translate-nl}, {@code dry-run}, {@code approve-intent} and
 * {@code reject-intent} RPCs (module {@code lighty-intent}).
 *
 * <p>This module is domain-agnostic: it is wired up with per-domain {@link IntentCompiler}s and
 * {@link DomainActuator}s (see lighty-intent-transport, lighty-intent-ran) rather than knowing
 * about any domain itself. An intent whose domain has no registered compiler fails validation;
 * one with no registered actuator can still be dry-run but fails at {@code approve-intent}.
 *
 * <p>Fulfilment reporting ({@link #publishReport}) is exposed for callers (e.g. a scheduled
 * task in the app aggregator) to invoke periodically; this module does not poll on its own in
 * this version.
 */
public class IntentLightyModule extends AbstractLightyModule {

    private static final Logger LOG = LoggerFactory.getLogger(IntentLightyModule.class);

    private final DataBroker dataBroker;
    private final RpcProviderService rpcProviderService;
    private final Map<String, IntentCompiler> compilersByDomain;
    private final Map<String, DomainActuator> actuatorsByDomain;
    private final Map<String, AssuranceSource> assuranceSourcesByDomain;
    private final List<ConflictRule> conflictRules;
    private final NlTranslator nlTranslator;

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private IntentStoreAccess store;

    public IntentLightyModule(final DataBroker dataBroker, final RpcProviderService rpcProviderService,
            final Map<String, IntentCompiler> compilersByDomain, final Map<String, DomainActuator> actuatorsByDomain,
            final Map<String, AssuranceSource> assuranceSourcesByDomain, final List<ConflictRule> conflictRules,
            final NlTranslator nlTranslator) {
        this.dataBroker = dataBroker;
        this.rpcProviderService = rpcProviderService;
        this.compilersByDomain = Map.copyOf(compilersByDomain);
        this.actuatorsByDomain = Map.copyOf(actuatorsByDomain);
        this.assuranceSourcesByDomain = Map.copyOf(assuranceSourcesByDomain);
        this.conflictRules = List.copyOf(conflictRules);
        this.nlTranslator = nlTranslator;
    }

    @Override
    protected boolean initProcedure() throws InterruptedException, ServletException {
        LOG.info("Initializing lighty-intent-core...");
        this.store = new IntentStoreAccess(dataBroker);
        final var conflictDetector = new ConflictDetector(conflictRules);
        final var pipeline = new IntentPipeline(store, compilersByDomain, conflictDetector);

        final InstanceIdentifier<Intent> intentWildcard = InstanceIdentifier.builder(Intents.class)
                .child(Intent.class)
                .build();
        final var listener = new IntentChangeListener(store, pipeline, actuatorsByDomain);
        closeables.add(dataBroker.registerDataTreeChangeListener(
                DataTreeIdentifier.create(LogicalDatastoreType.CONFIGURATION, intentWildcard), listener));

        closeables.add(rpcProviderService.registerRpcImplementation(new DryRunRpc(store, pipeline)));
        closeables.add(rpcProviderService.registerRpcImplementation(new TranslateNlRpc(nlTranslator)));
        closeables.add(rpcProviderService.registerRpcImplementation(
                new ApproveIntentRpc(store, pipeline, actuatorsByDomain)));
        closeables.add(rpcProviderService.registerRpcImplementation(new RejectIntentRpc(store)));

        LOG.info("lighty-intent-core initialized.");
        return true;
    }

    @Override
    protected boolean stopProcedure() throws InterruptedException, ExecutionException {
        LOG.info("Stopping lighty-intent-core...");
        boolean success = true;
        for (final AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("Failed to close {}", closeable, e);
                success = false;
            }
        }
        closeables.clear();
        LOG.info("lighty-intent-core stopped.");
        return success;
    }

    /**
     * Observes an {@code ACTIVE}/{@code DEGRADED} intent's expectation targets through the
     * {@link AssuranceSource} registered for its domain (if any) and appends the result as a
     * new {@code /intent-reports/intent-report} entry. Does nothing (returns {@code false}) for
     * an intent with no registered assurance source, or one that is not currently deployed.
     *
     * @param intentId the intent to observe and report on
     * @return {@code true} if a report was published
     */
    public boolean publishReport(final String intentId) {
        final var opsIntent = store.readOperationalIntent(intentId);
        final var configIntent = store.readConfigIntent(intentId);
        if (opsIntent.isEmpty() || configIntent.isEmpty()) {
            LOG.debug("Cannot report on unknown intent {}", intentId);
            return false;
        }
        final String domainKey = DomainKeys.keyOf(configIntent.get().getDomain());
        final AssuranceSource assuranceSource = domainKey == null ? null : assuranceSourcesByDomain.get(domainKey);
        if (assuranceSource == null) {
            LOG.debug("No AssuranceSource registered for domain {}; nothing to report for {}", domainKey, intentId);
            return false;
        }

        final List<ObservedTarget> observed = assuranceSource.observe(configIntent.get());
        final Map<TargetFulfilmentKey, TargetFulfilment> targetFulfilments = new java.util.LinkedHashMap<>();
        boolean anyFulfilled = false;
        boolean anyNotFulfilled = false;
        for (final ObservedTarget target : observed) {
            final var key = new TargetFulfilmentKey(target.getExpectationObjectInstance(),
                    target.getExpectationObjectType());
            targetFulfilments.put(key, new TargetFulfilmentBuilder()
                    .withKey(key)
                    .setObservedValue(target.getObservedValue())
                    .setFulfilmentStatus(target.getFulfilmentStatus())
                    .build());
            if (target.getFulfilmentStatus() == FulfilmentStatus.FULFILLED) {
                anyFulfilled = true;
            } else if (target.getFulfilmentStatus() == FulfilmentStatus.NOTFULFILLED) {
                anyNotFulfilled = true;
            }
        }
        final FulfilmentStatus overall;
        if (observed.isEmpty()) {
            overall = FulfilmentStatus.UNKNOWN;
        } else if (anyNotFulfilled && anyFulfilled) {
            overall = FulfilmentStatus.DEGRADED;
        } else if (anyNotFulfilled) {
            overall = FulfilmentStatus.NOTFULFILLED;
        } else {
            overall = FulfilmentStatus.FULFILLED;
        }

        final DateAndTime now = new DateAndTime(OffsetDateTime.now(ZoneOffset.UTC).toString());
        store.appendReport(intentId, now, overall, targetFulfilments);
        LOG.info("Published intent-report for {}: {}", intentId, overall);
        return true;
    }

    Set<String> registeredDomains() {
        return compilersByDomain.keySet();
    }
}
