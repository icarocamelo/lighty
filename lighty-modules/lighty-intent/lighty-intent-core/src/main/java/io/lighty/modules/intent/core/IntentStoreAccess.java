/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.core;

import com.google.common.util.concurrent.FluentFuture;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.DateAndTime;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.FulfilmentStatus;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentLifecycleState;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.IntentReports;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.Intents;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.IntentReportBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.IntentReportKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.intent.report.TargetFulfilment;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.intent.report.TargetFulfilmentKey;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.Intent;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentBuilder;
import org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intents.IntentKey;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the {@code /intents/intent} tree. Deliberately keeps the config-true fields
 * (an intent's spec, as submitted by the client) and the config-false fields (lifecycle-state,
 * timestamps, approved-by, active-plan, all owned by lighty-intent-core) apart: the former is
 * read from {@link LogicalDatastoreType#CONFIGURATION}, the latter is written and read from
 * {@link LogicalDatastoreType#OPERATIONAL}, matching how those leaves are modeled in
 * lighty-intent.yang. A caller that needs "the current state of an intent" reads both and joins
 * them by {@code intent-id}, the same way a RESTCONF GET without a content= parameter would.
 */
final class IntentStoreAccess {

    private static final Logger LOG = LoggerFactory.getLogger(IntentStoreAccess.class);
    private static final long DATASTORE_TIMEOUT_SECONDS = 30;
    private static final EnumSet<IntentLifecycleState> ACTIVE_STATES =
            EnumSet.of(IntentLifecycleState.ACTIVE, IntentLifecycleState.DEGRADED,
                    IntentLifecycleState.AWAITINGAPPROVAL, IntentLifecycleState.DEPLOYING);

    private final DataBroker dataBroker;

    IntentStoreAccess(final DataBroker dataBroker) {
        this.dataBroker = dataBroker;
    }

    static InstanceIdentifier<Intent> intentPath(final String intentId) {
        return InstanceIdentifier.builder(Intents.class)
                .child(Intent.class, new IntentKey(intentId))
                .build();
    }

    Optional<Intent> readConfigIntent(final String intentId) {
        return read(LogicalDatastoreType.CONFIGURATION, intentPath(intentId));
    }

    Optional<Intent> readOperationalIntent(final String intentId) {
        return read(LogicalDatastoreType.OPERATIONAL, intentPath(intentId));
    }

    /**
     * @param excludeIntentId an intent-id to leave out (typically the candidate being validated)
     * @return every intent whose operational lifecycle-state is ACTIVE, DEGRADED,
     *     AWAITING_APPROVAL or DEPLOYING, read from CONFIGURATION (so callers get the intent's
     *     spec, not its state)
     */
    List<Intent> readActiveIntents(final String excludeIntentId) {
        final Optional<Intents> operational = read(LogicalDatastoreType.OPERATIONAL,
                InstanceIdentifier.create(Intents.class));
        final List<Intent> result = new ArrayList<>();
        if (operational.isEmpty()) {
            return result;
        }
        final Map<IntentKey, Intent> operationalIntents = operational.get().getIntent();
        if (operationalIntents == null) {
            return result;
        }
        for (final Intent opsIntent : operationalIntents.values()) {
            final String intentId = opsIntent.getIntentId();
            if (intentId.equals(excludeIntentId)) {
                continue;
            }
            if (opsIntent.getLifecycleState() != null && ACTIVE_STATES.contains(opsIntent.getLifecycleState())) {
                readConfigIntent(intentId).ifPresent(result::add);
            }
        }
        return result;
    }

    /**
     * Merges the operational (config-false) fields of one intent. Only fields set on
     * {@code operationalFields} are touched; leaving the config-true fields unset on the
     * builder does not clear them, since this is a merge into a datastore that never held them
     * in the first place.
     */
    void mergeOperational(final String intentId, final IntentBuilder operationalFields) {
        operationalFields.setIntentId(intentId);
        final var writeTx = dataBroker.newWriteOnlyTransaction();
        writeTx.merge(LogicalDatastoreType.OPERATIONAL, intentPath(intentId), operationalFields.build());
        try {
            writeTx.commit().get(DATASTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Failed to merge operational state of intent {}", intentId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while merging operational state of intent {}", intentId, e);
        }
    }

    void deleteOperational(final String intentId) {
        final var writeTx = dataBroker.newWriteOnlyTransaction();
        writeTx.delete(LogicalDatastoreType.OPERATIONAL, intentPath(intentId));
        try {
            writeTx.commit().get(DATASTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Failed to delete operational state of intent {}", intentId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while deleting operational state of intent {}", intentId, e);
        }
    }

    /**
     * Appends one {@code /intent-reports/intent-report} entry.
     */
    void appendReport(final String intentId, final DateAndTime reportTimestamp, final FulfilmentStatus status,
            final Map<TargetFulfilmentKey, TargetFulfilment> targetFulfilments) {
        final var reportKey = new IntentReportKey(intentId, reportTimestamp);
        final var report = new IntentReportBuilder()
                .withKey(reportKey)
                .setFulfilmentStatus(status)
                .setTargetFulfilment(targetFulfilments)
                .build();
        final InstanceIdentifier<org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.IntentReport>
                reportPath = InstanceIdentifier.builder(IntentReports.class)
                .child(org.opendaylight.yang.gen.v1.urn.lighty.intent.rev260926.intent.reports.IntentReport.class,
                        reportKey)
                .build();
        final var writeTx = dataBroker.newWriteOnlyTransaction();
        writeTx.put(LogicalDatastoreType.OPERATIONAL, reportPath, report);
        try {
            writeTx.commit().get(DATASTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Failed to append intent-report for intent {}", intentId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while appending intent-report for intent {}", intentId, e);
        }
    }

    private <T extends org.opendaylight.yangtools.binding.DataObject> Optional<T> read(
            final LogicalDatastoreType store, final InstanceIdentifier<T> path) {
        final var readTx = dataBroker.newReadOnlyTransaction();
        final FluentFuture<Optional<T>> future = readTx.read(store, path);
        try {
            return future.get(DATASTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Failed to read {} from {}", path, store, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while reading {} from {}", path, store, e);
            return Optional.empty();
        }
    }
}
