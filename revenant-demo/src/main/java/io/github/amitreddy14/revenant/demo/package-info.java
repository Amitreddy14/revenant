/**
 * The crash demo: an order-fulfilment workflow, the activities it calls, and a worker process
 * that can be killed at any moment (see {@code scripts/demo-kill-worker.ps1}).
 *
 * <p>Each activity records its side effect in a {@link io.github.amitreddy14.revenant.demo.Ledger}
 * file, which is how the demo proves that a finished step is never repeated.
 */
package io.github.amitreddy14.revenant.demo;
