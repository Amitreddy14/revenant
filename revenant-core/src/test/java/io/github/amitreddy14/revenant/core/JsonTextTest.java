package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonTextTest {

    @Test
    void plainTextIsJustWrappedInQuotes() {
        assertThat(JsonText.quote("hello")).isEqualTo("\"hello\"");
    }

    @Test
    void quotesAndBackslashesAreEscaped() {
        assertThat(JsonText.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }

    @Test
    void controlCharactersAreEscaped() {
        assertThat(JsonText.quote("line1\nline2\ttab\r")).isEqualTo("\"line1\\nline2\\ttab\\r\"");
        assertThat(JsonText.quote("\u0001")).isEqualTo("\"\\u0001\"");
    }

    @Test
    void nonAsciiTextPassesThroughUnchanged() {
        assertThat(JsonText.quote("caf\u00e9")).isEqualTo("\"caf\u00e9\"");
    }

    @Test
    void anEmptyStringBecomesTwoQuotes() {
        assertThat(JsonText.quote("")).isEqualTo("\"\"");
    }
}
