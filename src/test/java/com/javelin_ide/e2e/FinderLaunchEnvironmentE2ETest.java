package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.javelin_ide.build.ManagedProcess;
import com.javelin_ide.build.ProcessManager;
import com.javelin_ide.build.ShellCommand;
import com.javelin_ide.build.ShellEnvironment;
import com.javelin_ide.terminal.TerminalSession;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An app started from Finder has launchd's minimal PATH, so tools such as mvn are "not found" in its terminal and
 * builds, while the same app started from a shell works. A fake login shell stands in for the user's zsh and a
 * fake {@code mvn} for Homebrew's, so the whole chain runs for real: login shell -> environment -> process ->
 * terminal output.
 */
class FinderLaunchEnvironmentE2ETest {
    private static final String LAUNCHD_PATH = "/usr/bin:/bin:/usr/sbin:/sbin";
    @TempDir Path dir;
    private ProcessManager processes;
    private TerminalSession terminal;

    @BeforeEach void posixOnly() { assumeFalse(ShellCommand.isWindows(), "drives /bin/sh"); }

    @AfterEach void tearDown() {
        if (terminal != null) terminal.close();
        if (processes != null) processes.stopAll();
    }

    private Path executable(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /** A "login shell" that, like ~/.zprofile + ~/.zshrc, adds the tool directory and JAVA_HOME before running -c. */
    private Path fakeLoginShell(Path tools) throws Exception {
        return executable(dir.resolve("fake-zsh"),
            "PATH=\"" + tools + ":$PATH\"; export PATH\nJAVA_HOME=/fake/jdk; export JAVA_HOME\nexec /bin/sh -c \"$4\"");
    }

    private Map<String, String> resolveAsFromFinder(Path tools) throws Exception {
        Path shell = fakeLoginShell(tools);
        // the same call ShellEnvironment makes with the real $SHELL, but with Finder's environment as the "inherited" one
        return ShellEnvironment.resolveFrom(Map.of("PATH", LAUNCHD_PATH, "HOME", dir.toString()), shell.toString()).variables();
    }

    private List<String> runLine(ProcessManager pm, Map<String, String> launchdLike, String line) throws Exception {
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        pm.start("t", ShellCommand.wrap(line), dir.toFile(), launchdLike, new ProcessManager.Listener() {
            @Override public void onLine(ManagedProcess p, String l) { out.add(l); }
            @Override public void onExit(ManagedProcess p, int c) { done.countDown(); }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "did not finish: " + out);
        return out;
    }

    @Test void withoutTheLoginShellEnvironmentMvnIsNotFound() throws Exception {
        Path tools = dir.resolve("homebrew/bin");
        executable(tools.resolve("fake-mvn"), "echo Apache Maven 3.9.9");
        processes = new ProcessManager(Map::of);
        List<String> out = runLine(processes, Map.of("PATH", LAUNCHD_PATH), "fake-mvn -v 2>&1 || echo missing");
        assertTrue(out.stream().anyMatch(l -> l.equals("missing")), out.toString());
    }

    @Test void buildsAndCommandsSeeToolsFromTheLoginShell() throws Exception {
        Path tools = dir.resolve("homebrew/bin");
        executable(tools.resolve("fake-mvn"), "echo Apache Maven 3.9.9; echo JAVA_HOME=$JAVA_HOME");
        Map<String, String> env = resolveAsFromFinder(tools);
        processes = new ProcessManager(() -> env);
        List<String> out = runLine(processes, Map.of(), "fake-mvn -v");
        assertEquals(List.of("Apache Maven 3.9.9", "JAVA_HOME=/fake/jdk"), out);
    }

    @Test void theTerminalSeesToolsFromTheLoginShell() throws Exception {
        Path tools = dir.resolve("homebrew/bin");
        executable(tools.resolve("fake-mvn"), "echo Apache Maven 3.9.9");
        Map<String, String> env = resolveAsFromFinder(tools);
        processes = new ProcessManager(() -> env);
        List<String> lines = new CopyOnWriteArrayList<>();
        terminal = new TerminalSession("term", processes, () -> dir.toFile(), new TerminalSession.Listener() {
            @Override public void onOutput(String line) { lines.add(line); }
            @Override public void onStateChanged() { }
        });
        terminal.submit("fake-mvn -v");
        long deadline = System.currentTimeMillis() + 15_000;
        while (!lines.contains("Apache Maven 3.9.9") && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(lines.contains("Apache Maven 3.9.9"), lines.toString());
    }

    @Test void explicitPerCallVariablesStillWinOverTheLoginShellOnes() throws Exception {
        processes = new ProcessManager(() -> Map.of("JAVA_HOME", "/from/login/shell"));
        assertEquals(List.of("/explicit"), runLine(processes, Map.of("JAVA_HOME", "/explicit"), "echo $JAVA_HOME"));
    }
}
