package io.github.amitreddy14.revenant.sdk;

/**
 * The code of one activity: the part of a workflow that touches the outside world.
 *
 * <p>An activity may run more than once for the same call (a worker can crash after doing the work
 * but before reporting it), so it should be idempotent or use the call's identity to deduplicate.
 * Throwing any exception reports a failure that the retry policy may retry; throw
 * {@link NonRetryableException} to fail at once.
 */
@FunctionalInterface
public interface ActivityFunction {

    /**
     * @param inputJson the JSON the workflow passed to the call
     * @return the result as JSON; it becomes the return value of the call in the workflow
     */
    String execute(String inputJson) throws Exception;
}
