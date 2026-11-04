package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CoreTypesTest {

    @Test
    void newEventRejectsBlankType() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NewEvent("  ", "{}"));
    }

    @Test
    void newEventRequiresAPayload() {
        assertThatNullPointerException().isThrownBy(() -> new NewEvent("WorkflowStarted", null));
    }

    @Test
    void historySequenceNumbersStartAtOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryEvent(0, "WorkflowStarted", "{}", Instant.now()));
        assertThat(new HistoryEvent(1, "WorkflowStarted", "{}", Instant.now()).seq()).isEqualTo(1);
    }

    @Test
    void commandIdsStartAtOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NewEvent("ActivityScheduled", 0L, "charge", "{}"));
        assertThat(Events.activityScheduled(1, "charge", "{}").commandId()).isEqualTo(1L);
    }

    @Test
    void onlyWorkflowCompletedAndFailedAreTerminal() {
        assertThat(EventType.isTerminal(EventType.WORKFLOW_COMPLETED)).isTrue();
        assertThat(EventType.isTerminal(EventType.WORKFLOW_FAILED)).isTrue();
        assertThat(EventType.isTerminal(EventType.ACTIVITY_COMPLETED)).isFalse();
    }

    @Test
    void onlyRunningWorkflowsAreOpen() {
        assertThat(WorkflowStatus.RUNNING.isClosed()).isFalse();
        assertThat(WorkflowStatus.COMPLETED.isClosed()).isTrue();
        assertThat(WorkflowStatus.FAILED.isClosed()).isTrue();
        assertThat(WorkflowStatus.CANCELLED.isClosed()).isTrue();
        assertThat(WorkflowStatus.TIMED_OUT.isClosed()).isTrue();
    }
}
