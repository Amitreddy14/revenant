package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class WorkflowIdTest {

    @Test
    void randomIdsAreUnique() {
        assertThat(WorkflowId.random()).isNotEqualTo(WorkflowId.random());
    }

    @Test
    void roundTripsThroughItsStringForm() {
        WorkflowId id = WorkflowId.random();

        assertThat(WorkflowId.parse(id.toString())).isEqualTo(id);
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new WorkflowId(null));
    }

    @Test
    void rejectsMalformedText() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowId.parse("not-a-uuid"));
    }
}
