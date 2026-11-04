package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeciderTest {

    /** charge -> reserve -> ship, each step feeding the next. */
    private static final WorkflowDefinition ORDER_FLOW = (ctx, input) -> {
        String charge = ctx.callActivity("charge", input);
        String stock = ctx.callActivity("reserve", charge);
        return ctx.callActivity("ship", stock);
    };

    @Test
    void aNewWorkflowSchedulesItsFirstActivityAndPauses() {
        var history = TestHistories.of(Events.workflowStarted("{\"amount\":5}"));

        Decision decision = Decider.decide(ORDER_FLOW, history);

        assertThat(decision.commands())
                .containsExactly(new Command.ScheduleActivity(1, "charge", "{\"amount\":5}"));
        assertThat(TestHistories.types(decision.events())).containsExactly("ActivityScheduled");
        assertThat(decision.closesWorkflowAs().isPresent()).isFalse();
    }

    @Test
    void aRecordedResultIsAnsweredFromHistoryAndTheNextStepIsScheduled() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "\"charged\""));

        Decision decision = Decider.decide(ORDER_FLOW, history);

        assertThat(decision.commands()).containsExactly(new Command.ScheduleActivity(2, "reserve", "\"charged\""));
    }

    @Test
    void anActivityThatIsStillRunningMeansNothingToDoYet() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.activityScheduled(1, "charge", "{}"));

        assertThat(Decider.decide(ORDER_FLOW, history).isEmpty()).isTrue();
    }

    @Test
    void whenEveryStepHasAResultTheWorkflowCompletes() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "\"a\""),
                Events.activityScheduled(2, "reserve", "\"a\""),
                Events.activityCompleted(2, "reserve", "\"b\""),
                Events.activityScheduled(3, "ship", "\"b\""),
                Events.activityCompleted(3, "ship", "\"shipped\""));

        Decision decision = Decider.decide(ORDER_FLOW, history);

        assertThat(decision.commands()).containsExactly(new Command.CompleteWorkflow("\"shipped\""));
        assertThat(decision.closesWorkflowAs().get()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(TestHistories.types(decision.events())).containsExactly("WorkflowCompleted");
    }

    @Test
    void aWorkflowThatAlreadyFinishedHasNothingLeftToDecide() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.workflowCompleted("1"));

        assertThat(Decider.decide(ORDER_FLOW, history).isEmpty()).isTrue();
    }

    @Test
    void anUnhandledActivityFailureFailsTheWorkflow() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityFailed(1, "charge", "{\"reason\":\"card declined\"}"));

        Decision decision = Decider.decide(ORDER_FLOW, history);

        assertThat(decision.closesWorkflowAs().get()).isEqualTo(WorkflowStatus.FAILED);
        Command.FailWorkflow failure = (Command.FailWorkflow) decision.commands().get(0);
        assertThat(failure.errorJson()).contains("ActivityFailedException");
        assertThat(failure.errorJson()).contains("card declined");
    }

    @Test
    void workflowCodeCanCatchAFailureAndCompensate() {
        WorkflowDefinition withRefund = (ctx, input) -> {
            try {
                return ctx.callActivity("charge", input);
            } catch (ActivityFailedException e) {
                return ctx.callActivity("notifyCustomer", e.errorJson());
            }
        };
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityFailed(1, "charge", "\"declined\""));

        Decision decision = Decider.decide(withRefund, history);

        assertThat(decision.commands()).containsExactly(new Command.ScheduleActivity(2, "notifyCustomer", "\"declined\""));
    }

    @Test
    void theCodeRunsFromTheTopOnEveryDecision() {
        AtomicInteger runs = new AtomicInteger();
        WorkflowDefinition counting = (ctx, input) -> {
            runs.incrementAndGet();
            return ctx.callActivity("charge", input);
        };
        var history = TestHistories.of(Events.workflowStarted("{}"));

        Decider.decide(counting, history);
        Decider.decide(counting, history);

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void aNullResultIsRecordedAsJsonNull() {
        var history = TestHistories.of(Events.workflowStarted("{}"));

        Decision decision = Decider.decide((ctx, input) -> null, history);

        assertThat(decision.commands()).containsExactly(new Command.CompleteWorkflow("null"));
    }

    @Test
    void failureMessagesAreEscapedIntoValidJson() {
        var history = TestHistories.of(Events.workflowStarted("{}"));

        Decision decision = Decider.decide((ctx, input) -> {
            throw new IllegalStateException("bad \"quote\"\nsecond line");
        }, history);

        Command.FailWorkflow failure = (Command.FailWorkflow) decision.commands().get(0);
        assertThat(failure.errorJson())
                .isEqualTo("{\"type\":\"java.lang.IllegalStateException\","
                        + "\"message\":\"bad \\\"quote\\\"\\nsecond line\"}");
    }

    @Test
    void codeThatSwallowsThePauseCannotSneakInMoreThanOneNewCommand() {
        WorkflowDefinition naughty = (ctx, input) -> {
            try {
                ctx.callActivity("first", "1");
            } catch (Throwable ignored) {
                // wrongly keeps going after the engine paused it
            }
            return ctx.callActivity("second", "2");
        };
        var history = TestHistories.of(Events.workflowStarted("{}"));

        Decision decision = Decider.decide(naughty, history);

        assertThat(decision.commands()).containsExactly(new Command.ScheduleActivity(1, "first", "1"));
    }

    @Test
    void changingTheNameOfARecordedStepIsDetected() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "chargeCard", "{}"),
                Events.activityCompleted(1, "chargeCard", "1"));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history))
                .isInstanceOf(NonDeterminismException.class)
                .hasMessageContaining("chargeCard")
                .hasMessageContaining("charge");
    }

    @Test
    void codeThatStopsEarlierThanTheHistoryIsDetected() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "1"),
                Events.activityScheduled(2, "reserve", "1"));
        WorkflowDefinition shorter = (ctx, input) -> ctx.callActivity("charge", input);

        assertThatThrownBy(() -> Decider.decide(shorter, history))
                .isInstanceOf(NonDeterminismException.class)
                .hasMessageContaining("reserve");
    }

    @Test
    void aMismatchIsStillReportedWhenTheWorkflowCodeCatchesIt() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "chargeCard", "{}"),
                Events.activityCompleted(1, "chargeCard", "1"));
        WorkflowDefinition hidesErrors = (ctx, input) -> {
            try {
                return ctx.callActivity("charge", input);
            } catch (RuntimeException e) {
                return "\"recovered\"";
            }
        };

        assertThatThrownBy(() -> Decider.decide(hidesErrors, history)).isInstanceOf(NonDeterminismException.class);
    }

    @Test
    void anEmptyHistoryIsRejected() {
        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, List.of())).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void aHistoryThatDoesNotStartWithWorkflowStartedIsRejected() {
        var history = List.of(TestHistories.at(1, Events.activityScheduled(1, "charge", "{}")));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void gapsInSequenceNumbersAreRejected() {
        var history = List.of(
                TestHistories.at(1, Events.workflowStarted("{}")),
                TestHistories.at(3, Events.activityScheduled(1, "charge", "{}")));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history))
                .isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining("expected sequence number 2");
    }

    @Test
    void aResultForAnActivityThatWasNeverScheduledIsRejected() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.activityCompleted(1, "charge", "1"));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void twoResultsForTheSameActivityAreRejected() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "1"),
                Events.activityCompleted(1, "charge", "2"));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void commandNumbersMustCountUpWithoutSkipping() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.activityScheduled(2, "reserve", "{}"));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void eventsAfterTheWorkflowClosedAreRejected() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"), Events.workflowCompleted("1"), Events.activityScheduled(1, "charge", "{}"));

        assertThatThrownBy(() -> Decider.decide(ORDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }
}
