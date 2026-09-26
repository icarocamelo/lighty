/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import io.lighty.modules.intent.api.DomainActuator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.annotation.NonNull;
import org.opendaylight.mdsal.binding.api.DataObjectModification;
import org.opendaylight.mdsal.binding.api.DataTreeChangeListener;
import org.opendaylight.mdsal.binding.api.DataTreeModification;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.DateAndTime;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-validates every intent written to the CONFIG datastore: on create/update it runs
 * {@link IntentPipeline}, then mirrors the outcome (lifecycle-state, lifecycle-detail,
 * timestamps, active-plan) to the OPERATIONAL datastore per lighty-intent.yang's DRAFT ->
 * VALIDATED/AWAITING_APPROVAL/FAILED transitions. On delete, it removes the operational mirror
 * and, if the intent was actuated, calls the matching {@link DomainActuator#remove}.
 */
final class IntentChangeListener implements DataTreeChangeListener<Intent> {

    private static final Logger LOG = LoggerFactory.getLogger(IntentChangeListener.class);

    private final IntentStoreAccess store;
    private final IntentPipeline pipeline;
    private final Map<String, DomainActuator> actuatorsByDomain;

    IntentChangeListener(final IntentStoreAccess store, final IntentPipeline pipeline,
            final Map<String, DomainActuator> actuatorsByDomain) {
        this.store = store;
        this.pipeline = pipeline;
        this.actuatorsByDomain = actuatorsByDomain;
    }

    @Override
    public void onDataTreeChanged(@NonNull final List<DataTreeModification<Intent>> changes) {
        for (final DataTreeModification<Intent> change : changes) {
            final DataObjectModification<Intent> rootNode = change.getRootNode();
            switch (rootNode.getModificationType()) {
                case WRITE, SUBTREE_MODIFIED -> handleWritten(rootNode.getDataAfter());
                case DELETE -> handleDeleted(rootNode.getDataBefore());
                default -> LOG.warn("Unsupported intent modification type: {}", rootNode.getModificationType());
            }
        }
    }

    private void handleWritten(final Intent intent) {
        if (intent == null) {
            return;
        }
        final String intentId = intent.getIntentId();
        LOG.info("Intent {} written to CONFIG, running validate/conflict/compile pipeline", intentId);

        final PipelineResult result = pipeline.run(intent);
        final String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        final boolean isNew = store.readOperationalIntent(intentId).isEmpty();

        final IntentBuilder operational = new IntentBuilder()
                .setLastUpdatedTimestamp(new DateAndTime(now));
        if (isNew) {
            operational.setCreatedTimestamp(new DateAndTime(now));
        }

        if (result.isFeasible()) {
            operational.setLifecycleState(IntentLifecycleState.AWAITINGAPPROVAL)
                    .setLifecycleDetail("Dry-run succeeded; awaiting operator approval.")
                    .setActivePlan(new org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent
                            .ActivePlanBuilder()
                            .setDomainCompiler(result.getDomainKey())
                            .setRenderedConfigJson(result.getRenderedConfigJson())
                            .setFeasible(Boolean.TRUE)
                            .setPlanTimestamp(new DateAndTime(now))
                            .build());
        } else {
            operational.setLifecycleState(IntentLifecycleState.FAILED)
                    .setLifecycleDetail(String.join("; ", result.getProblems()))
                    .setActivePlan(new org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.intent
                            .ActivePlanBuilder()
                            .setDomainCompiler(result.getDomainKey())
                            .setFeasible(Boolean.FALSE)
                            .setConflicts(Set.copyOf(result.getProblems()))
                            .setPlanTimestamp(new DateAndTime(now))
                            .build());
        }

        store.mergeOperational(intentId, operational);
        LOG.info("Intent {} lifecycle-state -> {}", intentId, result.isFeasible()
                ? IntentLifecycleState.AWAITINGAPPROVAL : IntentLifecycleState.FAILED);
    }

    private void handleDeleted(final Intent intentBefore) {
        if (intentBefore == null) {
            return;
        }
        final String intentId = intentBefore.getIntentId();
        LOG.info("Intent {} deleted from CONFIG", intentId);

        final var operational = store.readOperationalIntent(intentId);
        store.deleteOperational(intentId);

        final String domainKey = DomainKeys.keyOf(intentBefore.getDomain());
        final DomainActuator actuator = domainKey == null ? null : actuatorsByDomain.get(domainKey);
        final boolean wasActuated = operational.isPresent() && operational.get().getLifecycleState() != null
                && switch (operational.get().getLifecycleState()) {
                    case ACTIVE, DEGRADED, DEPLOYING -> true;
                    default -> false;
                };
        if (actuator != null && wasActuated) {
            LOG.info("Removing actuated configuration for deleted intent {}", intentId);
            actuator.remove(intentId);
        }
    }
}
