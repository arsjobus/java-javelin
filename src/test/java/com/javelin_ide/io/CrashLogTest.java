package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashLogTest {
    @TempDir Path dir;

    @Test void reportsHoldTheTraceAndEnvironmentButNoDocumentText() throws IOException {
        Throwable error = new IllegalStateException("outer", new RuntimeException("root cause"));
        Path report = CrashLog.write(dir.resolve("crashes"), "JavaFX Application Thread", error);
        String text = Files.readString(report);
        assertTrue(report.getFileName().toString().matches("crash-\\d{8}-\\d{6}-\\d{3}\\.log"));
        assertTrue(text.startsWith("javelin IDE crash report\ntime: "));
        assertTrue(text.contains("thread: JavaFX Application Thread"));
        assertTrue(text.contains("java: " + System.getProperty("java.version")));
        assertTrue(text.contains("os: " + System.getProperty("os.name")));
        assertTrue(text.contains("IllegalStateException: outer") && text.contains("Caused by: java.lang.RuntimeException: root cause"));
    }

    @Test void reportsWrittenInTheSameMillisecondDoNotOverwriteEachOther() throws IOException {
        Path a = CrashLog.write(dir, "t", new Error("1")), b = CrashLog.write(dir, "t", new Error("2")), c = CrashLog.write(dir, "t", new Error("3"));
        assertEquals(3, java.util.Set.of(a, b, c).size());
        try (Stream<Path> files = Files.list(dir)) { assertEquals(3, files.count()); }
    }

    @Test void onlyTheNewestReportsAreKept() throws IOException {
        for (int i = 0; i < CrashLog.KEEP + 5; i++) Files.writeString(dir.resolve(String.format("crash-20200101-0000%02d-000.log", i)), "old " + i);
        Files.writeString(dir.resolve("unrelated.txt"), "keep me");
        Path fresh = CrashLog.write(dir, "t", new Error("new"));
        try (Stream<Path> files = Files.list(dir)) {
            long reports = files.filter(p -> p.getFileName().toString().startsWith("crash-")).count();
            assertEquals(CrashLog.KEEP, reports);
        }
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(dir.resolve("unrelated.txt")));
        assertFalse(Files.exists(dir.resolve("crash-20200101-000000-000.log")));
    }

    @Test void installedHandlerWritesAReportAndNotifiesTheCaller() throws Exception {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        PrintStream originalErr = System.err;
        AtomicReference<Path> written = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (PrintStream ignored = new PrintStream(OutputStream.nullOutputStream())) {
            System.setErr(ignored);
            assertSame(original, CrashLog.install(dir.resolve("c"), p -> { written.set(p); latch.countDown(); }));
            Thread t = new Thread(() -> { throw new IllegalArgumentException("boom on a worker"); }, "worker-7");
            t.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertTrue(Files.readString(written.get()).contains("worker-7"));
            assertTrue(Files.readString(written.get()).contains("boom on a worker"));
        } finally {
            System.setErr(originalErr);
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    @Test void failingToWriteTheReportStillNotifiesWithNull() throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where a folder should be");
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        PrintStream originalErr = System.err;
        AtomicReference<Path> written = new AtomicReference<>(Path.of("sentinel"));
        CountDownLatch latch = new CountDownLatch(1);
        try (PrintStream ignored = new PrintStream(OutputStream.nullOutputStream())) {
            System.setErr(ignored);
            CrashLog.install(blocker, p -> { written.set(p); latch.countDown(); throw new IllegalStateException("callback failure is swallowed"); });
            new Thread(() -> { throw new Error("x"); }).start();
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertNull(written.get());
        } finally {
            System.setErr(originalErr);
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }
}
