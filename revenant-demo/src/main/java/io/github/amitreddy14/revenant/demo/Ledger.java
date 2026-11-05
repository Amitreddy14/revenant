package io.github.amitreddy14.revenant.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * An append-only text file standing in for "the outside world": charging a card, reserving stock
 * and shipping a parcel are recorded here as lines. Every line is written and closed at once, so a
 * process killed a moment later has still left it on disk.
 *
 * <p>Format: {@code 14:03:22.114 | charge order=A-1 amount=4999}. The demo script counts these
 * lines to prove which steps ran how many times.
 */
public final class Ledger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path file;

    public Ledger(Path file) {
        this.file = file;
    }

    /** Appends one line; safe to call from several threads. */
    public synchronized void record(String event) {
        String line = LocalTime.now().format(TIME) + " | " + event + System.lineSeparator();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the ledger " + file, e);
        }
    }

    public Path file() {
        return file;
    }
}
