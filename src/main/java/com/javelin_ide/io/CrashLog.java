package com.javelin_ide.io;

import com.javelin_ide.Branding;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Writes a local crash report when something throws that nothing handled. The report holds the stack trace, the
 * thread name, the time and the Java/OS version, and never the contents of any document. It stays on disk next to
 * the settings; nothing is sent anywhere. JavaFX-free.
 */
public final class CrashLog {
    static final int KEEP = 20;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private CrashLog() {}

    /** Writes a report and returns its path. Old reports beyond the newest {@value #KEEP} are removed. */
    public static Path write(Path directory, String threadName, Throwable error) throws IOException {
        Files.createDirectories(directory);
        Instant now = Instant.now();
        Path file = directory.resolve("crash-" + STAMP.format(now) + ".log");
        for (int n = 1; Files.exists(file); n++) file = directory.resolve("crash-" + STAMP.format(now) + "-" + n + ".log");
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        Files.writeString(file, Branding.FULL_NAME + " crash report\n"
            + "time: " + now + "\nthread: " + threadName + "\n"
            + "java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
            + "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + "\n\n"
            + trace);
        prune(directory);
        return file;
    }

    /**
     * Makes uncaught exceptions on any thread produce a report, then calls {@code onWritten} with its path (or null if
     * the report itself could not be written). Returns the handler it replaced so callers can restore it.
     */
    public static Thread.UncaughtExceptionHandler install(Path directory, Consumer<Path> onWritten) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Path report = null;
            try { report = write(directory, thread.getName(), error); } catch (IOException | RuntimeException ignored) { /* never fail while reporting a failure */ }
            error.printStackTrace();
            try { onWritten.accept(report); } catch (RuntimeException ignored) { }
        });
        return previous;
    }

    private static void prune(Path directory) throws IOException {
        List<Path> reports;
        try (Stream<Path> files = Files.list(directory)) {
            reports = files.filter(p -> p.getFileName().toString().startsWith("crash-") && p.getFileName().toString().endsWith(".log")).sorted().toList();
        }
        for (int i = 0; i < reports.size() - KEEP; i++) Files.deleteIfExists(reports.get(i));
    }
}
