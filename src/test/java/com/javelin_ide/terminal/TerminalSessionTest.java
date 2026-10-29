package com.javelin_ide.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.javelin_ide.build.ProcessManager;
import com.javelin_ide.build.ShellCommand;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives a real /bin/sh: the session exists to manage a live process, so mocking it would prove little. */
class TerminalSessionTest {
    @TempDir Path dir;
    private final ProcessManager processes = new ProcessManager(java.util.Map::of);
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final AtomicInteger stateChanges = new AtomicInteger();
    private TerminalSession session;

    @BeforeEach void setUp() {
        assumeFalse(ShellCommand.isWindows(), "these tests drive a POSIX shell");
        session = new TerminalSession("test-terminal", processes, dir::toFile, new TerminalSession.Listener() {
            @Override public void onOutput(String line) { output.add(line); }
            @Override public void onStateChanged() { stateChanges.incrementAndGet(); }
        });
    }

    @AfterEach void tearDown() { if (session != null) session.close(); processes.stopAll(); }

    private void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) { session.tick(); Thread.sleep(25); }
        assertTrue(condition.getAsBoolean(), "timed out; output so far: " + output);
    }

    @Test void startsAShellLazilyInTheRequestedDirectoryAndEchoesOutput() throws Exception {
        assertFalse(session.isBusy());
        assertEquals(0, session.backgroundJobs());
        assertEquals("", session.foreground());
        session.submit("pwd; echo hello");
        until(() -> output.contains("hello"));
        assertTrue(output.get(0).startsWith("[starting shell in "));
        assertTrue(output.contains(dir.toRealPath().toString()) || output.contains(dir.toString()), output.toString());
    }

    @Test void aRunningCommandMakesTheSessionBusyAndCtrlCStopsItWithoutKillingTheShell() throws Exception {
        session.submit("sleep 30");
        until(session::isBusy);
        assertEquals("sleep 30", session.foreground());
        assertTrue(session.interrupt());
        until(() -> !session.isBusy());
        assertEquals("", session.foreground());
        assertFalse(session.interrupt(), "nothing is running any more");
        session.submit("echo still-alive");
        until(() -> output.contains("still-alive"));
    }

    @Test void interruptWithoutAShellIsHarmless() { assertFalse(session.interrupt()); }

    @Test void typedLinesDuringACommandGoToItsStdin() throws Exception {
        session.submit("sh -c 'read answer; echo got:$answer'"); // an external command: a builtin `read` would never count as busy
        until(session::isBusy);
        session.submit("forty-two");
        until(() -> output.contains("got:forty-two"));
    }

    @Test void trailingAmpersandStartsABackgroundJobThatCtrlCLeavesAlone() throws Exception {
        session.submit("sleep 30 &");
        until(() -> session.backgroundJobs() == 1);
        assertFalse(session.isBusy(), "a background job is not the foreground command");
        session.submit("sleep 30");
        until(session::isBusy);
        assertTrue(session.interrupt());
        until(() -> !session.isBusy());
        assertEquals(1, session.backgroundJobs(), "Ctrl+C spared the background job");
    }

    @Test void aBackgroundJobThatFinishesImmediatelyIsForgotten() throws Exception {
        session.submit("true &");
        session.submit("echo after");
        until(() -> output.contains("after"));
        Thread.sleep(200);
        session.tick();
        assertEquals(0, session.backgroundJobs());
        assertFalse(session.isBusy());
    }

    @Test void exitIfIdleOnlyExitsAnIdleShell() throws Exception {
        assertFalse(session.exitIfIdle(), "no shell yet");
        session.submit("sleep 30");
        until(session::isBusy);
        assertFalse(session.exitIfIdle(), "busy: Ctrl+D must not kill the running command's shell");
        session.interrupt();
        until(() -> !session.isBusy());
        assertTrue(session.exitIfIdle());
        until(() -> output.stream().anyMatch(l -> l.startsWith("[shell exited with code "))); // `exit` reports the last status, here the interrupted sleep's 130
        assertFalse(session.exitIfIdle());
        assertFalse(session.interrupt());
        assertEquals(0, session.backgroundJobs());
    }

    @Test void theShellRestartsAfterItExits() throws Exception {
        session.submit("exit 4");
        until(() -> output.contains("[shell exited with code 4]"));
        session.submit("echo reborn");
        until(() -> output.contains("reborn"));
        assertEquals(2, output.stream().filter(l -> l.startsWith("[starting shell")).count());
    }

    @Test void anActiveVirtualenvIsDetectedWhenIdleAndHiddenFromOutput() throws Exception {
        assertTrue(session.venvName().isEmpty());
        session.submit("export VIRTUAL_ENV=/projects/demo/.venv");
        until(() -> session.venvName().isPresent());
        assertEquals(".venv", session.venvName().get());
        assertTrue(output.stream().noneMatch(l -> l.contains(TerminalSession.MARKER)), "the probe's output must never reach the user");
        int before = stateChanges.get();
        session.submit("unset VIRTUAL_ENV");
        until(() -> session.venvName().isEmpty());
        assertTrue(stateChanges.get() > before);
    }

    @Test void theProbeIsNotSentWhileACommandIsRunning() throws Exception {
        session.submit("sh -c 'read x; echo done:$x'");
        until(session::isBusy);
        Thread.sleep(1_000); // longer than the idle threshold
        session.tick();
        session.submit("go");
        until(() -> output.contains("done:go"));
        assertTrue(output.stream().noneMatch(l -> l.contains("done:go") && l.contains(TerminalSession.MARKER)));
    }

    @Test void aVenvPathThatIsJustASlashStillHasAName() throws Exception {
        session.submit("export VIRTUAL_ENV=/");
        until(() -> session.venvName().isPresent());
        assertEquals("/", session.venvName().get());
    }

    @Test void closeStopsTheShell() throws Exception {
        session.submit("echo hi");
        until(() -> output.contains("hi"));
        session.close();
        until(() -> output.stream().anyMatch(l -> l.startsWith("[shell exited")));
        session.close();
    }

    @Test void backgroundJobDetectionIsConservative() {
        for (String yes : new String[] {"npm start &", "sleep 1&", "a && b &", "  make  &  "}) assertTrue(TerminalSession.launchesBackgroundJob(yes), yes);
        for (String no : new String[] {"a && b", "a &&", "echo hi", "&", " & ", "cat <&", "echo x >&", "", "echo '&' x"}) assertFalse(TerminalSession.launchesBackgroundJob(no), no);
    }

    @Test void anAmpersandInTheMiddleOfALineBackgroundsTheCommandBeforeIt() {
        for (String yes : new String[] {"sleep 30 & echo hi", "a & b", "a&b", "a & b &", "a ; b & c", "a | b & c", "(a) & b", "echo \"x\" & echo y"})
            assertTrue(TerminalSession.launchesBackgroundJob(yes), yes);
        assertEquals(1, TerminalSession.backgroundOperators("a & b"));
        assertEquals(2, TerminalSession.backgroundOperators("a & b &"));
        assertEquals(2, TerminalSession.backgroundOperators("a & b & c"));
    }

    @Test void ampersandsThatAreNotBackgroundOperatorsAreIgnored() {
        for (String no : new String[] {
                "echo \"a & b\"", "echo 'a & b'", "echo a \\& b", "echo a \\&", "echo hi # & later", "echo hi #&",
                "cmd 2>&1", "cmd >&2", "cmd &> out.log", "cmd &>> out.log", "cmd |& tee log", "cat <&3",
                "a && b", "a && b && c", "a || b", "& echo hi", "a ; & b", "echo \"unterminated &", "echo 'unterminated &"})
            assertFalse(TerminalSession.launchesBackgroundJob(no), no);
    }

    @Test void aHashInsideAWordIsNotAComment() {
        assertTrue(TerminalSession.launchesBackgroundJob("echo a#b &"));
        assertTrue(TerminalSession.launchesBackgroundJob("echo a#b & echo c"));
    }

    @Test void foregroundTailIsOnlyReportedWhenACommandFollowsTheLastAmpersand() {
        assertTrue(TerminalSession.hasForegroundTail("a & b"));
        assertTrue(TerminalSession.hasForegroundTail("a & b & c"));
        assertFalse(TerminalSession.hasForegroundTail("a & b &"));
        assertFalse(TerminalSession.hasForegroundTail("a &"));
        assertFalse(TerminalSession.hasForegroundTail("echo hi"));
        assertFalse(TerminalSession.hasForegroundTail("a && b"));
    }

    @Test void aMidLineAmpersandBackgroundsTheFirstCommandAndLeavesTheSecondInTheForeground() throws Exception {
        session.submit("sleep 31 & sleep 32");
        until(() -> session.backgroundJobs() == 1 && session.isBusy());
        assertEquals("sleep 31 & sleep 32", session.foreground());
        assertTrue(session.interrupt(), "Ctrl+C reaches the foreground command");
        until(() -> !session.isBusy());
        assertEquals(1, session.backgroundJobs(), "...but the job before the & survives");
    }

    @Test void twoAmpersandsOnOneLineStartTwoBackgroundJobs() throws Exception {
        session.submit("sleep 33 & sleep 34 &");
        until(() -> session.backgroundJobs() == 2);
        assertFalse(session.isBusy());
    }

    @Test void quotedAmpersandsDoNotCreateBackgroundJobs() throws Exception {
        session.submit("echo 'a & b'; sleep 35");
        until(session::isBusy);
        assertEquals(0, session.backgroundJobs());
        assertTrue(session.interrupt());
        until(() -> !session.isBusy());
        assertEquals(0, session.backgroundJobs());
    }

    @Test void windowsNeverDetectsBackgroundJobs() {
        String original = System.getProperty("os.name");
        try { System.setProperty("os.name", "Windows 11"); assertFalse(TerminalSession.launchesBackgroundJob("start x &")); }
        finally { System.setProperty("os.name", original); }
    }

    @Test void aMissingDirectoryFallsBackToTheHomeFolder() throws Exception {
        TerminalSession homeSession = new TerminalSession("home", processes, () -> null, new TerminalSession.Listener() {
            @Override public void onOutput(String line) { output.add(line); }
            @Override public void onStateChanged() { }
        });
        homeSession.submit("echo ok");
        until(() -> output.contains("ok"));
        assertTrue(output.get(0).contains(new File(System.getProperty("user.home")).toString()));
        homeSession.close();
    }
}
