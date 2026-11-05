package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

    private static final String KEY_A = "alpha-key-0123456789";
    private static final String KEY_B = "bravo-key-0123456789";

    @Test
    void noKeysMeansAuthenticationIsOff() {
        assertThat(ApiKeys.parse("").enabled()).isFalse();
        assertThat(ApiKeys.parse(null).enabled()).isFalse();
        assertThat(ApiKeys.parse(" , ,").enabled()).isFalse();
    }

    @Test
    void aConfiguredKeyIsAcceptedAsABearerToken() {
        ApiKeys keys = ApiKeys.parse(KEY_A);

        assertThat(keys.enabled()).isTrue();
        assertThat(keys.accepts("Bearer " + KEY_A)).isTrue();
    }

    @Test
    void aWrongOrMissingKeyIsRefused() {
        ApiKeys keys = ApiKeys.parse(KEY_A);

        assertThat(keys.accepts(null)).isFalse();
        assertThat(keys.accepts("")).isFalse();
        assertThat(keys.accepts("Bearer ")).isFalse();
        assertThat(keys.accepts("Bearer " + KEY_B)).isFalse();
        assertThat(keys.accepts("Bearer " + KEY_A + "x")).isFalse();
        assertThat(keys.accepts("Bearer " + KEY_A.substring(1))).isFalse();
    }

    @Test
    void theKeyMustComeAsABearerTokenNotInAnotherScheme() {
        ApiKeys keys = ApiKeys.parse(KEY_A);

        assertThat(keys.accepts(KEY_A)).isFalse();
        assertThat(keys.accepts("Basic " + KEY_A)).isFalse();
        assertThat(keys.accepts("bearer " + KEY_A)).isFalse();
    }

    @Test
    void severalKeysAreAllAcceptedSoKeysCanBeRotated() {
        ApiKeys keys = ApiKeys.parse(KEY_A + " , " + KEY_B);

        assertThat(keys.count()).isEqualTo(2);
        assertThat(keys.accepts("Bearer " + KEY_A)).isTrue();
        assertThat(keys.accepts("Bearer " + KEY_B)).isTrue();
    }

    @Test
    void surroundingSpacesInTheHeaderAreIgnored() {
        assertThat(ApiKeys.parse(KEY_A).accepts("Bearer  " + KEY_A + "  ")).isTrue();
    }

    @Test
    void aKeyThatIsTooShortIsRefusedAtStartup() {
        assertThatThrownBy(() -> ApiKeys.parse("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
    }

    @Test
    void whenNoKeysAreConfigured_nothingIsAccepted() {
        assertThat(ApiKeys.parse("").accepts("Bearer anything-at-all-123")).isFalse();
    }
}
