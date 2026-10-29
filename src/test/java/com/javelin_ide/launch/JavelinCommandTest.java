package com.javelin_ide.launch;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real {@code packaging/macos/Javelin} script with stand-ins for macOS's {@code open} and {@code pgrep}, so
 * what it hands to the app is checked on any Unix machine, and checks that every macOS packaging script is valid shell.
 */
class JavelinCommandTest {
    private static final Path SCRIPTS = Path.of("packaging", "macos");

    @TempDir Path work;
    Path stubs, log, config, project;

    private record Outcome(int exit, String out, String err) {}

    @BeforeEach void setUp() throws IOException {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "shell scripts");
        assertTrue(Files.isRegularFile(SCRIPTS.resolve("Javelin")), "run the tests from the project root");
        stubs = Files.createDirectory(work.resolve("stubs"));
        log = work.resolve("open.log");
        config = work.resolve("config");
        project = Files.createDirectory(work.resolve("my project"));   // a space on purpose: quoting must hold
        stub("open", "for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$STUB_LOG\"; done\nexit \"${STUB_OPEN_EXIT:-0}\"\n");
        stub("pgrep", "[ \"${STUB_RUNNING:-0}\" = 1 ]\n");
    }

    private void stub(String name, String body) throws IOException {
        Path file = stubs.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Outcome run(Path cwd, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("sh", SCRIPTS.resolve("Javelin").toAbsolutePath().toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        pb.environment().put("PATH", stubs + ":/usr/bin:/bin");
        pb.environment().put("STUB_LOG", log.toString());
        pb.environment().put("JAVELIN_IDE_CONFIG_DIR", config.toString());
        pb.environment().putAll(env);
        pb.redirectOutput(work.resolve("stdout.txt").toFile()).redirectError(work.resolve("stderr.txt").toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "script hung");
        return new Outcome(p.exitValue(), Files.readString(work.resolve("stdout.txt")), Files.readString(work.resolve("stderr.txt")));
    }

    private List<String> openCalls() throws IOException { return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of(); }

    @Test void startsTheAppWithTheCurrentDirectoryWhenItIsNotRunning() throws Exception {
        Outcome o = run(project, Map.of());
        assertEquals(0, o.exit(), o.err());
        assertEquals(List.of("-n", "-a", "Javelin IDE", "--args", project.toRealPath().toString()), openCalls());
        assertFalse(Files.exists(config.resolve("open-requests")), "nothing to hand over: the argument carries it");
    }

    @Test void aRelativeDirectoryBecomesAnAbsolutePhysicalPath() throws Exception {
        Files.createDirectories(work.resolve("a/b"));
        Outcome o = run(work.resolve("a"), Map.of(), "b/../b");
        assertEquals(0, o.exit(), o.err());
        assertEquals(List.of("-n", "-a", "Javelin IDE", "--args", work.resolve("a/b").toRealPath().toString()), openCalls());
    }

    @Test void aRunningAppIsNotReusedTheFolderGetsItsOwnInstance() throws Exception {
        Outcome o = run(project, Map.of("STUB_RUNNING", "1"));   // pgrep says Javelin IDE is already running
        assertEquals(0, o.exit(), o.err());
        assertEquals(List.of("-n", "-a", "Javelin IDE", "--args", project.toRealPath().toString()), openCalls(),
            "-n starts a new instance; without it macOS would drop --args for a running app and show the old window");
        assertFalse(Files.exists(config.resolve("open-requests")), "no request file: the running window is not involved");
    }

    @Test void runningJavelinInSeveralFoldersStartsOneInstancePerFolder() throws Exception {
        Path second = Files.createDirectory(work.resolve("other"));
        Path third = Files.createDirectory(work.resolve("third"));
        assertEquals(0, run(project, Map.of()).exit());
        assertEquals(0, run(second, Map.of("STUB_RUNNING", "1")).exit());   // the first instance is up by now
        assertEquals(0, run(work, Map.of("STUB_RUNNING", "1"), "third").exit());
        assertEquals(List.of(
            "-n", "-a", "Javelin IDE", "--args", project.toRealPath().toString(),
            "-n", "-a", "Javelin IDE", "--args", second.toRealPath().toString(),
            "-n", "-a", "Javelin IDE", "--args", third.toRealPath().toString()), openCalls());
    }

    @Test void aFileOrMissingPathIsRefused() throws Exception {
        Files.writeString(work.resolve("f.txt"), "x");
        for (String bad : List.of("f.txt", "missing")) {
            Outcome o = run(work, Map.of(), bad);
            assertEquals(1, o.exit());
            assertTrue(o.err().contains("not a directory: " + bad), o.err());
        }
        assertEquals(List.of(), openCalls(), "the app is never started for a bad path");
    }

    @Test void moreThanOneDirectoryIsRefused() throws Exception {
        Outcome o = run(work, Map.of(), "a", "b");
        assertEquals(2, o.exit());
        assertTrue(o.err().contains("at most one directory"));
        assertEquals(List.of(), openCalls());
    }

    @Test void helpPrintsUsageAndDoesNothing() throws Exception {
        for (String flag : List.of("-h", "--help")) {
            Outcome o = run(work, Map.of(), flag);
            assertEquals(0, o.exit());
            assertTrue(o.out().startsWith("usage: Javelin [directory]"), o.out());
        }
        assertEquals(List.of(), openCalls());
    }

    @Test void aMissingAppGivesAHelpfulMessageAndAFailureExit() throws Exception {
        Outcome o = run(project, Map.of("STUB_OPEN_EXIT", "1"));
        assertEquals(1, o.exit());
        assertTrue(o.err().contains("Is it installed?"), o.err());
    }

    @Test void everyPackagingScriptIsValidShellAndTheCommandIsExecutable() throws Exception {
        for (String name : List.of("Javelin", "build-app.sh", "install.sh")) {
            Path script = SCRIPTS.resolve(name);
            assertTrue(Files.isExecutable(script), name + " must be executable");
            Process p = new ProcessBuilder("sh", "-n", script.toString()).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, p.waitFor(), name + ": " + output);
        }
    }
}
