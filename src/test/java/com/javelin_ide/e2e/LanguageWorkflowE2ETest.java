package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.javelin_ide.build.Problem;
import com.javelin_ide.build.ShellCommand;
import com.javelin_ide.io.ProjectPaths;
import com.javelin_ide.language.LanguageProblems;
import com.javelin_ide.language.LanguageService;
import com.javelin_ide.language.Lsp;
import com.javelin_ide.language.RenamePlan;
import com.javelin_ide.language.TextEdits;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Language menu's path with a real server process: start the server, open a file, receive diagnostics, ask for a
 * rename, plan it, and apply it to the files. Everything the window does for these steps except drawing it.
 */
class LanguageWorkflowE2ETest {
    @TempDir Path dir;
    private final Map<URI, List<Lsp.Diagnostic>> diagnostics = new ConcurrentHashMap<>();
    private LanguageService service;
    private File a;
    private File b;

    @BeforeEach void setUp() throws Exception {
        assumeFalse(ShellCommand.isWindows(), "the fake server is launched through /bin/sh");
        service = new LanguageService(diagnostics::put, line -> { }, () -> { });
        a = dir.resolve("A.java").toFile();
        b = dir.resolve("B.java").toFile();
        Files.writeString(a.toPath(), "class Foo {\n  foo();\n}\n");
        Files.writeString(b.toPath(), "class Bar {\n  foo();\n}\n");
    }

    @AfterEach void tearDown() { if (service != null) service.stopAll(); }

    private void startServer() throws Exception {
        String command = "'" + Path.of(System.getProperty("java.home"), "bin", "java") + "' -cp '" + System.getProperty("java.class.path")
            + "' com.javelin_ide.language.FakeLanguageServer '" + dir.resolve("server.log") + "' workspace";
        service.start("java", command, dir.toFile()).get(30, TimeUnit.SECONDS);
    }

    private void awaitDiagnostics(File file) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!diagnostics.containsKey(file.toURI()) && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertTrue(diagnostics.containsKey(file.toURI()), "no diagnostics arrived for " + file);
    }

    @Test void diagnosticsReachTheProblemsPanelWithProjectRelativePaths() throws Exception {
        service.open(a, Files.readString(a.toPath()));
        startServer();
        awaitDiagnostics(a);
        List<Problem> problems = LanguageProblems.toProblems(ProjectPaths.display(dir.toFile(), a), diagnostics.get(a.toURI()));
        assertEquals(List.of(new Problem(Problem.Severity.WARNING, "A.java", 2, 3, "fake problem  [fake]")), problems);
    }

    @Test void aRenameIsPlannedShownAndAppliedToEveryAffectedFile() throws Exception {
        String text = Files.readString(a.toPath());
        service.open(a, text);
        startServer();

        int caret = TextEdits.offset(text, new Lsp.Position(1, 3));
        Lsp.WorkspaceEdit edit = service.rename(a, caret, "bar").get(30, TimeUnit.SECONDS);
        assertFalse(edit.unsupported());

        RenamePlan plan = RenamePlan.of(edit, ProjectPaths::fromUri);
        assertEquals(3, plan.total());
        assertEquals(0, plan.skipped());
        assertEquals(List.of(a, b), plan.byFile().keySet().stream().sorted().toList());
        assertTrue(plan.summary("foo", "bar", f -> ProjectPaths.display(dir.toFile(), f)).startsWith("Rename foo to bar: 3 edit(s) in 2 file(s)."));

        for (Map.Entry<File, List<Lsp.TextEdit>> e : plan.byFile().entrySet())
            Files.writeString(e.getKey().toPath(), TextEdits.apply(Files.readString(e.getKey().toPath()), e.getValue()));

        assertEquals("// renamed to bar\nclass Foo {\n  bar();\n}\n", Files.readString(a.toPath()));
        assertEquals("class Bar {\n  bar();\n}\n", Files.readString(b.toPath()));
    }
}
