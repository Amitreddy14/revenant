package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void noneNeverRetries() {
        assertThat(RetryPolicy.none().delayAfterFailure(1)).isEmpty();
    }

    @Test
    void exponentialBackoffDoublesTheWaitAndStopsAtMaxAttempts() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(1));

        List<Long> waits = new ArrayList<>();
        for (int failed = 1; failed < 5; failed++) {
            waits.add(policy.delayAfterFailure(failed).orElseThrow().toSeconds());
        }

        assertThat(waits).containsExactly(1L, 2L, 4L, 8L);
        assertThat(policy.delayAfterFailure(5)).isEmpty();
        assertThat(policy.delayAfterFailure(6)).isEmpty();
    }

    @Test
    void aWaitIsNeverLongerThanTheMaximumInterval() {
        RetryPolicy policy = RetryPolicy.exponential(10, Duration.ofSeconds(1)).withMaxInterval(Duration.ofSeconds(5));

        assertThat(policy.delayAfterFailure(1).orElseThrow()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayAfterFailure(2).orElseThrow()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfterFailure(3).orElseThrow()).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayAfterFailure(4).orElseThrow()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfterFailure(9).orElseThrow()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aCoefficientOfOneKeepsTheWaitConstant() {
        RetryPolicy policy = RetryPolicy.exponential(4, Duration.ofMillis(250)).withBackoffCoefficient(1.0);

        assertThat(policy.delayAfterFailure(1).orElseThrow()).isEqualTo(Duration.ofMillis(250));
        assertThat(policy.delayAfterFailure(3).orElseThrow()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void aZeroInitialIntervalRetriesImmediately() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ZERO, 2.0, Duration.ZERO);

        assertThat(policy.delayAfterFailure(1).orElseThrow()).isEqualTo(Duration.ZERO);
        assertThat(policy.delayAfterFailure(2).orElseThrow()).isEqualTo(Duration.ZERO);
        assertThat(policy.delayAfterFailure(3)).isEmpty();
    }

    @Test
    void aHugeNumberOfAttemptsDoesNotOverflow() {
        RetryPolicy policy = RetryPolicy.exponential(Integer.MAX_VALUE, Duration.ofSeconds(1));

        assertThat(policy.delayAfterFailure(5_000).orElseThrow()).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), Double.NaN, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.none().delayAfterFailure(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionsDefaultToNoRetriesAndNoTimeout() {
        ActivityOptions defaults = ActivityOptions.defaults();

        assertThat(defaults.retryPolicy()).isEqualTo(RetryPolicy.none());
        assertThat(defaults.startToCloseTimeout()).isNull();
    }

    @Test
    void aTimeoutMustBePositive() {
        assertThatThrownBy(() -> ActivityOptions.defaults().withStartToCloseTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityOptions.defaults().withStartToCloseTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failureResultsAreRetryableUnlessMarkedOtherwise() {
        assertThat(ActivityResult.failure("{}").retryable()).isTrue();
        assertThat(ActivityResult.nonRetryableFailure("{}").retryable()).isFalse();
        assertThat(ActivityResult.success("1").retryable()).isFalse();
        assertThat(new ActivityResult(true, "1", true).retryable()).isFalse();
    }
}
