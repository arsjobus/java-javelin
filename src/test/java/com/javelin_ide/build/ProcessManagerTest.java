package com.javelin_ide.build;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessManagerTest {
    @TempDir Path dir;
    private final ProcessManager manager = new ProcessManager(java.util.Map::of);
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final CountDownLatch exited = new CountDownLatch(1);
    private volatile int exitCode = Integer.MIN_VALUE;

    private final ProcessManager.Listener listener = new ProcessManager.Listener() {
        @Override public void onLine(ManagedProcess p, String line) { lines.add(line); }
        @Override public void onExit(ManagedProcess p, int code) { exitCode = code; exited.countDown(); }
    };

    @BeforeEach void posixOnly() { assumeFalse(ShellCommand.isWindows(), "these tests drive /bin/sh"); }

    @AfterEach void cleanUp() { manager.stopAll(); }

    private boolean waitExit() throws InterruptedException { return exited.await(10, TimeUnit.SECONDS); }

    @Test void capturesOutputAndExitCode() throws Exception {
        ManagedProcess p = manager.start("echo", ShellCommand.wrap("echo one; echo two 1>&2; exit 3"), dir.toFile(), listener);
        assertTrue(waitExit());
        assertEquals(List.of("one", "two"), lines.stream().sorted().toList()); // stdout and stderr are merged; their relative order is not guaranteed
        assertEquals(3, exitCode);
        assertEquals("echo", p.name());
        assertEquals(dir.toFile(), p.directory());
        assertTrue(p.id() > 0);
        assertNotNull(p.started());
        assertTrue(p.toString().contains("echo"));
        assertTrue(manager.running().isEmpty());
    }

    @Test void ansiIsStrippedFromOutput() throws Exception {
        manager.start("colour", List.of("/bin/sh", "-c", "printf '\\033[31mred\\033[0m\\n'"), null, listener);
        assertTrue(waitExit());
        assertEquals(List.of("red"), lines);
    }

    @Test void extraEnvironmentIsLayeredOverTheInheritedOne() throws Exception {
        manager.start("env", ShellCommand.wrap("echo $CM_TEST_VAR"), dir.toFile(), Map.of("CM_TEST_VAR", "hello"), listener);
        assertTrue(waitExit());
        assertEquals(List.of("hello"), lines);
    }

    @Test void startFailureIsReportedToTheCaller() {
        assertThrows(IOException.class, () -> manager.start("bad", List.of("/definitely/not/a/binary"), dir.toFile(), listener));
        assertTrue(manager.running().isEmpty());
    }

    @Test void runningListsLiveProcessesInStartOrderAndStopAllKillsThem() throws Exception {
        ManagedProcess a = manager.start("a", ShellCommand.wrap("sleep 30"), dir.toFile(), listener);
        ManagedProcess b = manager.start("b", ShellCommand.wrap("sleep 30"), dir.toFile(), listener);
        assertEquals(List.of(a, b), manager.running());
        assertTrue(a.isAlive());
        manager.stopAll();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!manager.running().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(manager.running().isEmpty());
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
    }

    @Test void stoppingAFinishedProcessIsHarmless() throws Exception {
        ManagedProcess p = manager.start("quick", ShellCommand.wrap("true"), dir.toFile(), listener);
        assertTrue(waitExit());
        p.process().waitFor();
        manager.stop(p);
    }

    @Test void stubbornProcessesAreForceKilledAfterTheGracePeriod() throws Exception {
        ManagedProcess p = manager.start("stubborn", ShellCommand.wrap("trap '' TERM; echo ready; while true; do sleep 1; done"), dir.toFile(), listener);
        long deadline = System.currentTimeMillis() + 10_000;
        while (lines.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(List.of("ready"), lines);
        manager.stop(p);
        assertTrue(waitExit(), "the process should be force-killed after the grace period");
        assertFalse(p.isAlive());
    }

    @Test void listenerExceptionsDoNotStrandTheProcess() throws Exception {
        ProcessManager.Listener bad = new ProcessManager.Listener() {
            @Override public void onLine(ManagedProcess p, String line) { throw new IllegalStateException("boom"); }
            @Override public void onExit(ManagedProcess p, int code) { exitCode = code; exited.countDown(); throw new IllegalStateException("boom"); }
        };
        manager.start("noisy", ShellCommand.wrap("echo a; echo b; exit 5"), dir.toFile(), bad);
        assertTrue(waitExit(), "onExit must still be delivered");
        assertEquals(5, exitCode);
        assertTrue(manager.running().isEmpty());
    }

    @Test void sendWritesALineToStdin() throws Exception {
        ManagedProcess p = manager.start("cat", ShellCommand.wrap("read x; echo got:$x"), dir.toFile(), listener);
        p.send("ping");
        assertTrue(waitExit());
        assertEquals(List.of("got:ping"), lines);
    }

    @Test void interruptSignalsTheShellsChildrenButNotTheShell() throws Exception {
        ManagedProcess shell = manager.start("sh", ShellCommand.interactive(), dir.toFile(), listener);
        shell.send("sleep 30");
        long deadline = System.currentTimeMillis() + 10_000;
        while (shell.children().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertFalse(shell.children().isEmpty());
        assertEquals(1, manager.interrupt(shell));
        deadline = System.currentTimeMillis() + 10_000;
        while (!shell.children().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(shell.children().isEmpty());
        assertTrue(shell.isAlive(), "SIGINT must not take the shell down with it");
        assertEquals(0, manager.interrupt(shell));
    }

    @Test void interruptOnWindowsTerminatesInstead() throws Exception {
        String original = System.getProperty("os.name");
        ManagedProcess sleeper = manager.start("s", ShellCommand.wrap("exec sleep 30"), dir.toFile(), listener);
        try {
            System.setProperty("os.name", "Windows 11");
            assertEquals(1, manager.interrupt(List.of(sleeper.process().toHandle())));
        } finally { System.setProperty("os.name", original); }
        assertTrue(waitExit());
    }

    /**
     * A process with a zombie child on any shell: {@code true &} forks a child, then the shell {@code exec}s into
     * {@code sleep}, which never waits for it. (A plain idle dash leaves such a zombie, but bash, the macOS /bin/sh,
     * reaps background jobs at once, so relying on the shell's own behaviour is not portable.)
     */
    private ManagedProcess processWithZombieChild() throws Exception {
        ManagedProcess parent = manager.start("zombie-parent", ShellCommand.wrap("true & exec sleep 30"), dir.toFile(), listener);
        long deadline = System.currentTimeMillis() + 10_000;
        while (parent.process().children().findAny().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        Thread.sleep(200); // let the child finish and become a zombie
        assertEquals(1, parent.process().children().count(), "the finished child must still be listed by the OS");
        return parent;
    }

    @Test void finishedBackgroundJobsAreNotReportedAsChildren() throws Exception {
        ManagedProcess parent = processWithZombieChild();
        assertTrue(parent.children().isEmpty(), "a zombie is not a running child: " + parent.children());
    }

    @Test void zombieDetectionIsFalseForLiveUnknownAndWindowsProcesses() throws Exception {
        assertFalse(ManagedProcess.isZombie(ProcessHandle.current().pid()));
        assertFalse(ManagedProcess.isZombie(Integer.MAX_VALUE - 7));
        Path noProc = dir.resolve("no-proc"); // forces the `ps` fallback used on macOS
        assertFalse(ManagedProcess.isZombie(noProc, ProcessHandle.current().pid()));
        assertFalse(ManagedProcess.isZombie(noProc, Integer.MAX_VALUE - 7));
        long zombie = processWithZombieChild().process().children().findFirst().orElseThrow().pid();
        assertTrue(ManagedProcess.isZombie(zombie));
        assertTrue(ManagedProcess.isZombie(noProc, zombie));
        Path fakeProc = dir.resolve("fake-proc");
        Files.createDirectories(fakeProc.resolve("42"));
        Files.writeString(fakeProc.resolve("42").resolve("stat"), "42 (we)ird name) Z 1 2 3");
        assertTrue(ManagedProcess.isZombie(fakeProc, 42), "the state follows the LAST closing parenthesis");
        Files.writeString(fakeProc.resolve("42").resolve("stat"), "42 (x) S 1");
        assertFalse(ManagedProcess.isZombie(fakeProc, 42));
        Files.writeString(fakeProc.resolve("42").resolve("stat"), "garbage");
        assertFalse(ManagedProcess.isZombie(fakeProc, 42));
        Files.delete(fakeProc.resolve("42").resolve("stat"));
        Files.createDirectory(fakeProc.resolve("42").resolve("stat")); // readable, but reading it fails
        assertFalse(ManagedProcess.isZombie(fakeProc, 42));
        Process interrupted = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream("Z\n".getBytes()); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException { throw new InterruptedException("simulated"); }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
        try { assertFalse(ManagedProcess.isZombie(noProc, zombie, command -> interrupted), "an interrupted check gives up quietly"); assertTrue(Thread.currentThread().isInterrupted(), "and keeps the interrupt flag"); }
        finally { Thread.interrupted(); }
        assertFalse(ManagedProcess.isZombie(noProc, zombie, command -> { throw new IOException("ps is not installed"); }));
        String original = System.getProperty("os.name");
        try { System.setProperty("os.name", "Windows 11"); assertFalse(ManagedProcess.isZombie(1)); }
        finally { System.setProperty("os.name", original); }
    }

    @Test void managedProcessCommandIsDefensivelyCopied() throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("/bin/sh", "-c", "true"));
        ManagedProcess p = manager.start("copy", command, null, listener);
        command.clear();
        assertEquals(3, p.command().size());
        assertThrows(UnsupportedOperationException.class, () -> p.command().add("x"));
        assertTrue(waitExit());
    }

    /** A process whose behaviour the test controls: the real class can rarely be provoked into these failures. */
    private static final class FakeProcess extends Process {
        final java.io.InputStream out;
        final boolean interruptedOnWait;
        FakeProcess(java.io.InputStream out, boolean interruptedOnWait) { this.out = out; this.interruptedOnWait = interruptedOnWait; }
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return out; }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { if (interruptedOnWait) throw new InterruptedException("simulated"); return 7; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException { if (interruptedOnWait) throw new InterruptedException("simulated"); return true; }
        @Override public int exitValue() { return 7; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return true; }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() { return java.util.stream.Stream.empty(); }
    }

    @Test void aBrokenOutputStreamStillEndsInAnExitNotification() throws Exception {
        java.io.InputStream broken = new java.io.InputStream() { @Override public int read() throws IOException { throw new IOException("stream closed"); } };
        ProcessManager fake = new ProcessManager(builder -> new FakeProcess(broken, false));
        fake.start("fake", List.of("x"), null, listener);
        assertTrue(waitExit());
        assertEquals(7, exitCode);
        assertTrue(fake.running().isEmpty());
    }

    @Test void anInterruptedWaitReportsMinusOne() throws Exception {
        ProcessManager fake = new ProcessManager(builder -> new FakeProcess(java.io.InputStream.nullInputStream(), true));
        fake.start("fake", List.of("x"), null, listener);
        assertTrue(waitExit());
        assertEquals(-1, exitCode);
    }

    @Test void theKillerThreadSurvivesBeingInterrupted() throws Exception {
        ProcessManager fake = new ProcessManager(builder -> new FakeProcess(java.io.InputStream.nullInputStream(), true));
        ManagedProcess p = fake.start("fake", List.of("x"), null, listener);
        fake.stop(p); // the process "is alive" and its grace-period wait is interrupted: nothing may blow up
        Thread.sleep(200);
    }

    @Test void interruptWithoutAKillBinaryIsHarmlessAndInterruptedWaitsStop() throws Exception {
        ProcessManager noKill = new ProcessManager(builder -> { throw new IOException("kill not found"); });
        assertEquals(0, noKill.interrupt(List.of(ProcessHandle.current())));
        ProcessManager hung = new ProcessManager(builder -> new FakeProcess(java.io.InputStream.nullInputStream(), true));
        assertEquals(0, hung.interrupt(List.of(ProcessHandle.current())));
        assertTrue(Thread.interrupted(), "the interrupt flag is preserved");
    }
}
