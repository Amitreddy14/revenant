package io.github.amitreddy14.revenant.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The set of API keys the server accepts (ADR-0014). No Spring here, so it is easy to test.
 *
 * <p>Keys are compared by their SHA-256 hash with {@link MessageDigest#isEqual}, which takes the
 * same time whether the first or the last byte differs. That denies an attacker the "timing side
 * channel": measuring how long a refusal takes to learn how many leading characters were right.
 * Every stored key is checked on every request, so the time also does not reveal which key matched.
 */
final class ApiKeys {

    /** A key shorter than this is too easy to guess; the server refuses to start with one. */
    static final int MIN_LENGTH = 16;

    private static final String PREFIX = "Bearer ";

    private final List<byte[]> hashes;

    private ApiKeys(List<byte[]> hashes) {
        this.hashes = hashes;
    }

    /** @param commaSeparated for example {@code "key-one-xxxxxxxxxx,key-two-xxxxxxxxxx"}; blank means none */
    static ApiKeys parse(String commaSeparated) {
        List<byte[]> hashes = new ArrayList<>();
        if (commaSeparated != null) {
            for (String part : commaSeparated.split(",")) {
                String key = part.strip();
                if (key.isEmpty()) {
                    continue;
                }
                if (key.length() < MIN_LENGTH) {
                    throw new IllegalArgumentException(
                            "every API key must be at least " + MIN_LENGTH + " characters long");
                }
                hashes.add(sha256(key));
            }
        }
        return new ApiKeys(List.copyOf(hashes));
    }

    /** True if at least one key is configured, so authentication is switched on. */
    boolean enabled() {
        return !hashes.isEmpty();
    }

    int count() {
        return hashes.size();
    }

    /** @param authorizationHeader the raw {@code Authorization} header, may be null */
    boolean accepts(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] offered = sha256(authorizationHeader.substring(PREFIX.length()).strip());
        boolean match = false;
        for (byte[] known : hashes) {
            // No early exit: every key is compared, so timing does not reveal which one matched.
            match |= MessageDigest.isEqual(known, offered);
        }
        return match;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
