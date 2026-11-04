package io.github.amitreddy14.revenant.core;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of replaying a workflow: what must be written so the workflow can move forward.
 *
 * <p>An empty decision means "nothing to do yet": the workflow is waiting for an activity that is
 * still running, or it has already finished.
 */
public record Decision(List<Command> commands) {

    public Decision {
        commands = List.copyOf(commands);
    }

    public static Decision none() {
        return new Decision(List.of());
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    /** The history events to append, in order. */
    public List<NewEvent> events() {
        return commands.stream()
                .map(command -> switch (command) {
                    case Command.ScheduleActivity c -> Events.activityScheduled(c.commandId(), c.name(), c.inputJson());
                    case Command.CompleteWorkflow c -> Events.workflowCompleted(c.resultJson());
                    case Command.FailWorkflow c -> Events.workflowFailed(c.errorJson());
                })
                .toList();
    }

    /** The activity tasks to put on the queue. */
    public List<Command.ScheduleActivity> activitiesToSchedule() {
        return commands.stream()
                .filter(Command.ScheduleActivity.class::isInstance)
                .map(Command.ScheduleActivity.class::cast)
                .toList();
    }

    /** The final status if this decision ends the workflow. */
    public Optional<WorkflowStatus> closesWorkflowAs() {
        for (Command command : commands) {
            if (command instanceof Command.CompleteWorkflow) {
                return Optional.of(WorkflowStatus.COMPLETED);
            }
            if (command instanceof Command.FailWorkflow) {
                return Optional.of(WorkflowStatus.FAILED);
            }
        }
        return Optional.empty();
    }
}
