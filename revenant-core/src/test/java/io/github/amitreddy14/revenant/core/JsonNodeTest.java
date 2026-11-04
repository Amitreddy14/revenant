package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JsonNodeTest {

    @Test
    void readsObjectsArraysAndScalars() {
        JsonNode node = JsonNode.parse(" {\"a\": 1, \"b\": [true, false, null], \"c\": \"x\", \"d\": -2.5e3} ");

        assertThat(node.required("a").asLong()).isEqualTo(1L);
        assertThat(node.required("b").items().size()).isEqualTo(3);
        assertThat(node.required("b").items().get(0).asBoolean()).isTrue();
        assertThat(node.required("b").items().get(2).isNull()).isTrue();
        assertThat(node.required("c").asText()).isEqualTo("x");
        assertThat(node.required("d").asDouble()).isEqualTo(-2500.0);
    }

    @Test
    void rawGivesBackTheExactTextOfAValue() {
        JsonNode node = JsonNode.parse("{\"payload\":  {\"b\":1,   \"a\":[1, 2]}, \"n\": 7}");

        assertThat(node.required("payload").raw()).isEqualTo("{\"b\":1,   \"a\":[1, 2]}");
        assertThat(node.required("n").raw()).isEqualTo("7");
    }

    @Test
    void decodesStringEscapes() {
        JsonNode node = JsonNode.parse("\"line\\nbreak \\\"q\\\" \\u00e9 \\\\\"");

        assertThat(node.asText()).isEqualTo("line\nbreak \"q\" \u00e9 \\");
    }

    @Test
    void aMissingMemberIsNullAndOptionalReadersTolerateIt() {
        JsonNode node = JsonNode.parse("{\"a\": null}");

        assertThat(node.field("zzz")).isNull();
        assertThat(node.optText("a")).isNull();
        assertThat(node.optText("zzz")).isNull();
        assertThat(node.optLong("zzz")).isNull();
    }

    @Test
    void aRequiredMemberThatIsMissingIsReported() {
        assertThatThrownBy(() -> JsonNode.parse("{}").required("taskId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing field \"taskId\"");
    }

    @Test
    void readingTheWrongTypeIsReported() {
        assertThatThrownBy(() -> JsonNode.parse("\"x\"").asLong())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonNode.parse("1.5").asLong())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidJson() {
        for (String bad : new String[] {
            "", "{", "[1,]", "{\"a\":}", "{\"a\" 1}", "{a:1}", "01", "1.", "-", "tru", "\"abc", "[1] x",
            "{\"a\":1,}", "\"\\q\"", "\"\\u12\"", "nul", "{\"a\":1 \"b\":2}", "[1 2]"
        }) {
            assertThatThrownBy(() -> JsonNode.parse(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsAControlCharacterInsideAString() {
        assertThatThrownBy(() -> JsonNode.parse("\"a\nb\"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAbsurdNesting() {
        String deep = "[".repeat(200) + "]".repeat(200);

        assertThatThrownBy(() -> JsonNode.parse(deep)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullInputIsRejected() {
        assertThatThrownBy(() -> JsonNode.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
