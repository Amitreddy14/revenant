package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Timers and activity options in the replay engine. */
class TimerDeciderTest {

    /** charge, wait a day, then send a reminder. */
    private static final WorkflowDefinition REMINDER_FLOW = (ctx, input) -> {
        String charged = ctx.callActivity("charge", input);
        ctx.sleep(Duration.ofDays(1));
        return ctx.callActivity("remind", charged);
    };

    @Test
    void sleepingStartsATimerAndPauses() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "\"ok\""));

        Decision decision = Decider.decide(REMINDER_FLOW, history);

        assertThat(decision.commands()).containsExactly(new Command.StartTimer(2, 86_400_000L));
        assertThat(TestHistories.types(decision.events())).containsExactly("TimerStarted");
        assertThat(decision.events().get(0).payloadJson()).isEqualTo("{\"durationMillis\":86400000}");
        assertThat(decision.events().get(0).commandId()).isEqualTo(2L);
        assertThat(decision.timersToStart()).hasSize(1);
        assertThat(decision.activitiesToSchedule()).isEmpty();
    }

    @Test
    void aTimerThatHasNotFiredMeansNothingToDo() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "\"ok\""),
                Events.timerStarted(2, 86_400_000L));

        assertThat(Decider.decide(REMINDER_FLOW, history).isEmpty()).isTrue();
    }

    @Test
    void afterTheTimerFiredTheWorkflowContinuesWithTheNextStep() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "\"ok\""),
                Events.timerStarted(2, 86_400_000L),
                Events.timerFired(2));

        Decision decision = Decider.decide(REMINDER_FLOW, history);

        assertThat(decision.commands()).containsExactly(new Command.ScheduleActivity(3, "remind", "\"ok\""));
    }

    @Test
    void aSleepOfZeroStillGoesThroughTheTimer() {
        WorkflowDefinition flow = (ctx, input) -> {
            ctx.sleep(Duration.ZERO);
            return "1";
        };

        Decision decision = Decider.decide(flow, TestHistories.of(Events.workflowStarted("{}")));

        assertThat(decision.commands()).containsExactly(new Command.StartTimer(1, 0));
    }

    @Test
    void anActivityCallCarriesItsOptionsIntoTheCommand() {
        ActivityOptions options = ActivityOptions.retrying(RetryPolicy.exponential(4, Duration.ofSeconds(2)))
                .withStartToCloseTimeout(Duration.ofSeconds(30));
        WorkflowDefinition flow = (ctx, input) -> ctx.callActivity("charge", input, options);

        Decision decision = Decider.decide(flow, TestHistories.of(Events.workflowStarted("{}")));

        var scheduled = (Command.ScheduleActivity) decision.commands().get(0);
        assertThat(scheduled.options()).isEqualTo(options);
        assertThat(decision.activitiesToSchedule().get(0).options().startToCloseTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aPlainCallUsesTheDefaultOptions() {
        Decision decision = Decider.decide(
                (ctx, input) -> ctx.callActivity("charge", input), TestHistories.of(Events.workflowStarted("{}")));

        assertThat(((Command.ScheduleActivity) decision.commands().get(0)).options())
                .isEqualTo(ActivityOptions.defaults());
    }

    @Test
    void sleepingWhereTheHistoryHasAnActivityIsNonDeterminism() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"));
        WorkflowDefinition nowSleeps = (ctx, input) -> {
            ctx.sleep(Duration.ofSeconds(1));
            return "1";
        };

        assertThatThrownBy(() -> Decider.decide(nowSleeps, history))
                .isInstanceOf(NonDeterminismException.class)
                .hasMessageContaining("sleeps");
    }

    @Test
    void callingAnActivityWhereTheHistoryHasATimerIsNonDeterminism() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.timerStarted(1, 1000));
        WorkflowDefinition nowCalls = (ctx, input) -> ctx.callActivity("charge", input);

        assertThatThrownBy(() -> Decider.decide(nowCalls, history))
                .isInstanceOf(NonDeterminismException.class)
                .hasMessageContaining("timer");
    }

    @Test
    void codeThatStopsBeforeARecordedTimerIsNonDeterminism() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.activityScheduled(1, "charge", "{}"),
                Events.activityCompleted(1, "charge", "\"ok\""),
                Events.timerStarted(2, 1000));
        WorkflowDefinition stopsEarly = (ctx, input) -> ctx.callActivity("charge", input);

        assertThatThrownBy(() -> Decider.decide(stopsEarly, history))
                .isInstanceOf(NonDeterminismException.class)
                .hasMessageContaining("timer");
    }

    @Test
    void aMismatchIsStillReportedWhenWorkflowCodeCatchesIt() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.timerStarted(1, 1000));
        WorkflowDefinition swallows = (ctx, input) -> {
            try {
                return ctx.callActivity("charge", input);
            } catch (RuntimeException ignored) {
                return "carried on";
            }
        };

        assertThatThrownBy(() -> Decider.decide(swallows, history)).isInstanceOf(NonDeterminismException.class);
    }

    @Test
    void aNegativeSleepFailsTheWorkflow() {
        WorkflowDefinition flow = (ctx, input) -> {
            ctx.sleep(Duration.ofSeconds(-1));
            return "1";
        };

        Decision decision = Decider.decide(flow, TestHistories.of(Events.workflowStarted("{}")));

        assertThat(decision.closesWorkflowAs().get()).isEqualTo(WorkflowStatus.FAILED);
    }

    @Test
    void aFiredTimerWithoutAStartIsAnInvalidHistory() {
        var history = TestHistories.of(Events.workflowStarted("{}"), Events.timerFired(1));

        assertThatThrownBy(() -> Decider.decide(REMINDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void aTimerFiringCannotAnswerAnActivity() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"), Events.activityScheduled(1, "charge", "{}"), Events.timerFired(1));

        assertThatThrownBy(() -> Decider.decide(REMINDER_FLOW, history))
                .isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining("not a");
    }

    @Test
    void anActivityResultCannotAnswerATimer() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"), Events.timerStarted(1, 1000), Events.activityCompleted(1, "charge", "1"));

        assertThatThrownBy(() -> Decider.decide(REMINDER_FLOW, history)).isInstanceOf(InvalidHistoryException.class);
    }

    @Test
    void aTimerCannotFireTwice() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"),
                Events.timerStarted(1, 1000),
                Events.timerFired(1),
                Events.timerFired(1));

        assertThatThrownBy(() -> Decider.decide(REMINDER_FLOW, history))
                .isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining("more than one result");
    }

    @Test
    void timersAndActivitiesMustShareOneNumbering() {
        var history = TestHistories.of(
                Events.workflowStarted("{}"), Events.activityScheduled(1, "charge", "{}"), Events.timerStarted(3, 1000));

        assertThatThrownBy(() -> Decider.decide(REMINDER_FLOW, history))
                .isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining("command 2 was expected");
    }
}
