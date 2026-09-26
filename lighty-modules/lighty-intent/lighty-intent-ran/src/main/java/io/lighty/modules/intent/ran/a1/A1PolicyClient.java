/*
 * Copyright (c) 2026 icarocamelo. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.intent.ran.a1;

import java.io.IOException;

/**
 * Minimal client for the O-RAN Alliance A1-P interface exposed by a Near-RT RIC's A1 mediator:
 * creating/updating and deleting one A1 policy instance of a given policy type.
 *
 * <p><b>P2/P3 skeleton.</b> Nothing in this module currently calls this interface as part of
 * intent actuation &mdash; see this module's README.md. It exists, and is unit-tested against a
 * loopback HTTP server, so the shape of the RIC-facing call is real ahead of that wiring.
 */
public interface A1PolicyClient {

    /**
     * Creates or updates (A1-P policy PUT is idempotent) one policy instance.
     *
     * @param policyTypeId the A1 policy type id, e.g. {@link io.lighty.modules.intent.ran.RanCcoCompiler}'s
     *     placeholder {@code a1-policy-type-id}
     * @param policyId a caller-chosen identifier for this policy instance, e.g. the intent id
     * @param policyJson the policy body, whose schema is defined by {@code policyTypeId}
     * @throws IOException if the RIC could not be reached, or rejected the request
     * @throws InterruptedException if the calling thread is interrupted while waiting for the response
     */
    void createPolicy(String policyTypeId, String policyId, String policyJson) throws IOException, InterruptedException;

    /**
     * Deletes a previously created policy instance. Idempotent: deleting an already-absent
     * policy is not an error from the caller's point of view.
     *
     * @param policyTypeId the A1 policy type id the policy instance was created under
     * @param policyId the policy instance identifier passed to {@link #createPolicy}
     * @throws IOException if the RIC could not be reached, or rejected the request
     * @throws InterruptedException if the calling thread is interrupted while waiting for the response
     */
    void deletePolicy(String policyTypeId, String policyId) throws IOException, InterruptedException;
}
