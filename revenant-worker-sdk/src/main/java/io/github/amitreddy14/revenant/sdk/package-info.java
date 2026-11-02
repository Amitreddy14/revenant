/**
 * The Java SDK used to write workflows and activities and to run a worker that polls the
 * Revenant server for tasks.
 *
 * <p>Built in Phase 5. Workflow code must be deterministic so it can be replayed; the SDK
 * exposes the only approved ways to read time, generate randomness and sleep.
 */
package io.github.amitreddy14.revenant.sdk;
