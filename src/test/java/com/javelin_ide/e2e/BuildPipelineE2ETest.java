package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin_ide.build.Problem;
import com.javelin_ide.build.Problem.Severity;
import com.javelin_ide.build.ProblemParser;
import com.javelin_ide.build.ProcessManager;
import com.javelin_ide.build.ShellCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole Run > Build path with a real compiler: command -> process -> output lines -> parsed problems. */
class BuildPipelineE2ETest {
    @TempDir Path dir;
    private final ProcessManager processes = new ProcessManager(java.util.Map::of);

    @AfterEach void tearDown() { processes.stopAll(); }

    private record Result(List<String> lines, List<Problem> problems, int exit) {}

    private Result run(String command) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        ProblemParser parser = new ProblemParser();
        List<Problem> problems = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        int[] exit = {Integer.MIN_VALUE};
        processes.start("build", ShellCommand.wrap(command), dir.toFile(), new ProcessManager.Listener() {
            @Override public void onLine(com.javelin_ide.build.ManagedProcess p, String line) { lines.add(line); parser.accept(line).ifPresent(problems::add); }
            @Override public void onExit(com.javelin_ide.build.ManagedProcess p, int code) { exit[0] = code; done.countDown(); }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the build did not finish: " + lines);
        return new Result(lines, problems, exit[0]);
    }

    private String javac() { return "'" + Path.of(System.getProperty("java.home"), "bin", "javac") + "'"; }

    @Test void aBrokenJavaFileYieldsLocatedProblemsAndANonZeroExit() throws Exception {
        assumeTrue(Files.isExecutable(Path.of(System.getProperty("java.home"), "bin", "javac")), "needs a JDK");
        Files.writeString(dir.resolve("Broken.java"), "public class Broken {\n    void f() {\n        int x = missing;\n        String s = 5;\n    }\n}\n");
        Result r = run(javac() + " Broken.java");
        assertNotEquals(0, r.exit());
        assertEquals(2, r.problems().size(), r.lines().toString());
        assertEquals(new Problem(Severity.ERROR, "Broken.java", 3, 0, "cannot find symbol"), r.problems().get(0));
        assertEquals(4, r.problems().get(1).line());
        assertTrue(r.problems().get(1).message().contains("incompatible types"));
    }

    @Test void aCleanBuildHasNoProblemsAndExitsZero() throws Exception {
        assumeTrue(Files.isExecutable(Path.of(System.getProperty("java.home"), "bin", "javac")), "needs a JDK");
        Files.writeString(dir.resolve("Fine.java"), "public class Fine { }\n");
        Result r = run(javac() + " Fine.java");
        assertEquals(0, r.exit());
        assertTrue(r.problems().isEmpty());
        assertTrue(Files.exists(dir.resolve("Fine.class")));
    }

    @Test void warningsAreReportedAsWarnings() throws Exception {
        assumeTrue(Files.isExecutable(Path.of(System.getProperty("java.home"), "bin", "javac")), "needs a JDK");
        Files.writeString(dir.resolve("Warn.java"), "import java.util.*;\npublic class Warn {\n    @SuppressWarnings(\"unused\") List raw = new ArrayList();\n    void f() { Thread.currentThread().stop(); }\n}\n");
        Result r = run(javac() + " -Xlint:all Warn.java");
        assertTrue(r.problems().stream().anyMatch(p -> p.severity() == Severity.WARNING && p.line() == 3), r.lines().toString());
    }

    @Test void aPythonTracebackIsLocated() throws Exception {
        assumeTrue(!ShellCommand.isWindows() && new java.io.File("/usr/bin/python3").canExecute(), "needs python3");
        Files.writeString(dir.resolve("boom.py"), "def f():\n    return 1 / 0\n\nf()\n");
        Result r = run("python3 boom.py");
        assertNotEquals(0, r.exit());
        assertEquals(1, r.problems().size(), r.lines().toString());
        assertTrue(r.problems().get(0).file().endsWith("boom.py"));
        assertEquals(2, r.problems().get(0).line());
        assertTrue(r.problems().get(0).message().startsWith("ZeroDivisionError"));
    }

    @Test void aMissingCommandExitsNonZeroWithoutProblems() throws Exception {
        assumeTrue(!ShellCommand.isWindows());
        Result r = run("definitely-not-a-real-command-12345");
        assertNotEquals(0, r.exit());
        assertTrue(r.problems().isEmpty());
    }
}
