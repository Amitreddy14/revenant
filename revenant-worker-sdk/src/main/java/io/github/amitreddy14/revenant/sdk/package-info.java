/**
 * The Java SDK used to write workflows and activities and to run a worker that polls the
 * Revenant server for tasks.
 *
 * <p>Workflow code is a {@link io.github.amitreddy14.revenant.core.WorkflowDefinition}: ordinary
 * Java that calls activities through its context and must be deterministic so it can be replayed.
 * {@link io.github.amitreddy14.revenant.sdk.Worker} runs the code and
 * {@link io.github.amitreddy14.revenant.sdk.RevenantClient} talks to the server.
 */
package io.github.amitreddy14.revenant.sdk;
