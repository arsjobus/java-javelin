package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.javelin.build.ShellCommand;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end: the real service and session talk LSP over stdio to {@link FakeLanguageServer} running as a child process. */
class LanguageServiceTest {
    @TempDir Path dir;
    private Path log;
    private final Map<URI, List<Lsp.Diagnostic>> latest = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<URI> diagnosticCalls = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessionChanges = new AtomicInteger();
    private LanguageService service;
    private File java;

    @BeforeEach void setUp() throws IOException {
        assumeFalse(ShellCommand.isWindows(), "the fake server is launched through /bin/sh");
        log = dir.resolve("server.log");
        service = new LanguageService((uri, list) -> { diagnosticCalls.add(uri); latest.put(uri, list); }, logs::add, sessionChanges::incrementAndGet);
        java = new File(dir.toFile(), "A.java");
    }

    @AfterEach void tearDown() { if (service != null) service.stopAll(); }

    private String command(String mode) {
        return "'" + Path.of(System.getProperty("java.home"), "bin", "java") + "' -cp '" + System.getProperty("java.class.path") + "' com.javelin.language.FakeLanguageServer '" + log + "' " + mode;
    }

    private void start(String mode) throws Exception { service.start("java", command(mode), dir.toFile()).get(30, TimeUnit.SECONDS); }

    private List<String> serverLog() {
        try { return Files.isRegularFile(log) ? Files.readAllLines(log) : List.of(); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private void awaitLog(String prefix) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (serverLog().stream().anyMatch(l -> l.startsWith(prefix))) return;
            Thread.sleep(25);
        }
        fail("server never logged '" + prefix + "'; log: " + serverLog());
    }

    private String logLine(String prefix) { return serverLog().stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(prefix + " not in " + serverLog())); }

    private static void until(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertTrue(c.getAsBoolean(), "timed out");
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(30, TimeUnit.SECONDS); }

    private static Throwable failure(CompletableFuture<?> f) {
        ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(30, TimeUnit.SECONDS));
        return ex.getCause();
    }

    @Test void everyRequestRoundTripsThroughARealServerProcess() throws Exception {
        service.open(java, "class A {}\n");
        start("full");
        assertTrue(service.isRunning("java"));
        assertEquals(List.of("java"), service.runningLanguages());
        assertTrue(sessionChanges.get() >= 1);

        List<Lsp.CompletionItem> items = get(service.completion(java, 3));
        assertEquals("foo()", items.get(0).text());
        assertEquals("**hover** text", get(service.hover(java, 3)));
        assertEquals(URI.create("file:///def.java"), get(service.definition(java, 3)).get(0).uri());
        assertEquals(1, get(service.references(java, 3)).size());
        Lsp.WorkspaceEdit edit = get(service.rename(java, 3, "renamed"));
        assertEquals("renamed", edit.changes().values().iterator().next().get(0).newText());
        assertEquals("f(a)\n\nParameter: a", get(service.signatureHelp(java, 3)));
        assertEquals("Main", get(service.documentSymbols(java)).get(0).name());
        assertEquals("formatted", get(service.formatting(java, 4, true)).get(0).newText());
    }

    @Test void theServerSeesPreciseDocumentSyncAndSaves() throws Exception {
        service.open(java, "ab\ncd");
        service.open(java, "ignored second open");
        start("full");
        awaitLog("N textDocument/didOpen");
        assertTrue(logLine("N textDocument/didOpen").contains("\"text\":\"ab\\ncd\""));
        assertTrue(logLine("N textDocument/didOpen").contains("\"languageId\":\"java\""));

        service.change(java, 1, 3, "XY"); // replaces "b\nc"
        awaitLog("N textDocument/didChange");
        String change = logLine("N textDocument/didChange");
        assertTrue(change.contains("\"range\":{\"start\":{\"line\":0,\"character\":1},\"end\":{\"line\":1,\"character\":1}}") && change.contains("\"text\":\"XY\"") && change.contains("\"version\":2"), change);

        service.saved(java, "aXYd");
        awaitLog("N textDocument/didSave");
        assertTrue(logLine("N textDocument/didSave").contains("\"text\":\"aXYd\""));
        assertEquals(1, serverLog().stream().filter(l -> l.startsWith("N textDocument/didChange")).count(), "an in-sync save must not resend the text");

        service.close(java);
        awaitLog("N textDocument/didClose");
        service.close(java);
        service.close(null);
        service.change(java, 0, 0, "ignored once closed");
        assertEquals(1, serverLog().stream().filter(l -> l.startsWith("N textDocument/didClose")).count());
    }

    @Test void driftBetweenEditorAndServiceIsRepairedOnSave() throws Exception {
        service.open(java, "old");
        start("full");
        service.saved(java, "completely new text");
        awaitLog("N textDocument/didSave");
        String change = logLine("N textDocument/didChange");
        assertTrue(change.contains("\"text\":\"completely new text\"") && !change.contains("\"range\""), change);
    }

    @Test void filesOpenedAfterTheServerIsReadyAreAttachedAndSavingAnUntitledFileOpensIt() throws Exception {
        start("full");
        service.open(java, "late");
        awaitLog("N textDocument/didOpen");
        File other = new File(dir.toFile(), "B.java");
        service.saved(other, "saved first");
        until(() -> serverLog().stream().filter(l -> l.startsWith("N textDocument/didOpen")).count() == 2);
    }

    @Test void fullSyncServersGetTheWholeText() throws Exception {
        service.open(java, "abc");
        start("fullsync");
        service.change(java, 0, 1, "Z");
        awaitLog("N textDocument/didChange");
        String change = logLine("N textDocument/didChange");
        assertTrue(change.contains("\"text\":\"Zbc\"") && !change.contains("\"range\""), change);
        service.saved(java, "Zbc");
        Thread.sleep(300);
        assertTrue(serverLog().stream().noneMatch(l -> l.startsWith("N textDocument/didSave")), "this server did not ask for saves");
    }

    @Test void saveWithoutTextAndNoSyncModes() throws Exception {
        service.open(java, "abc");
        start("syncobj");
        service.saved(java, "abc");
        awaitLog("N textDocument/didSave");
        assertFalse(logLine("N textDocument/didSave").contains("\"text\""));
        service.close(java);
        service.stopAll();
        until(() -> !service.isRunning("java"));

        Files.deleteIfExists(log);
        service.open(java, "abc");
        start("nosync");
        service.change(java, 0, 1, "Q");
        service.saved(java, "Qbc");
        Thread.sleep(300);
        assertTrue(serverLog().stream().noneMatch(l -> l.startsWith("N textDocument/didChange")));
    }

    @Test void serversWithoutCapabilitiesAnswerWithAClearError() throws Exception {
        service.open(java, "abc");
        start("none");
        for (CompletableFuture<?> f : List.of(service.completion(java, 0), service.hover(java, 0), service.definition(java, 0), service.references(java, 0), service.rename(java, 0, "x"),
            service.signatureHelp(java, 0), service.documentSymbols(java), service.formatting(java, 4, true))) {
            Throwable cause = failure(f);
            assertTrue(cause instanceof UnsupportedOperationException, String.valueOf(cause));
            assertTrue(cause.getMessage().contains("java language server does not support"));
        }
    }

    @Test void requestsExplainWhyTheyCannotRun() throws Exception {
        Throwable noServer = failure(service.hover(new File(dir.toFile(), "Unopened.java"), 0));
        assertTrue(noServer.getMessage().contains("saved file with a recognized extension"));
        service.open(java, "x");
        assertTrue(failure(service.hover(java, 0)).getMessage().contains("No java language server is running"));
        File text = new File(dir.toFile(), "notes.txt");
        service.open(text, "ignored");
        service.saved(text, "ignored");
        assertTrue(failure(service.hover(text, 0)).getMessage().contains("recognized extension"));
    }

    @Test void startingTwiceOrInABrokenDirectoryFailsCleanly() throws Exception {
        start("full");
        IOException again = assertThrows(IOException.class, () -> service.start("java", command("full"), dir.toFile()));
        assertTrue(again.getMessage().contains("already running"));
        service.stopAll();
        until(() -> !service.isRunning("java"));
        assertThrows(IOException.class, () -> service.start("java", command("full"), new File(dir.toFile(), "no/such/dir")));
        assertFalse(service.isRunning("java"));
    }

    @Test void stoppingShutsTheServerDownAndClearsItsDiagnostics() throws Exception {
        service.open(java, "x");
        start("full");
        until(() -> latest.containsKey(java.toURI()) && !latest.get(java.toURI()).isEmpty());
        assertEquals("fake problem", latest.get(java.toURI()).get(0).message());
        service.stop("java");
        service.stop("cobol");
        until(() -> !service.isRunning("java"));
        until(() -> latest.get(java.toURI()).isEmpty());
        awaitLog("N exit");
        assertTrue(serverLog().contains("R shutdown"));
        assertTrue(service.runningLanguages().isEmpty());
    }

    @Test void aServerThatDiesIsReportedAndForgotten() throws Exception {
        service.open(java, "x");
        service.start("java", command("crash"), dir.toFile()).get(30, TimeUnit.SECONDS);
        until(() -> !service.isRunning("java"));
        until(() -> logs.stream().anyMatch(l -> l.contains("language server exited")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("with code 9")), logs.toString());
        Throwable cause = failure(service.hover(java, 0));
        assertTrue(cause.getMessage().contains("No java language server"));
    }

    @Test void aServerThatNeverCompletesTheHandshakeFailsReady() throws Exception {
        CompletableFuture<Void> ready = service.start("java", command("die-on-init"), dir.toFile());
        Throwable cause = failure(ready);
        assertNotNull(cause);
        until(() -> !service.isRunning("java"));
    }

    @Test void serverInitiatedTrafficIsAnsweredSafelyAndNeverApplied() throws Exception {
        start("probe");
        awaitLog("S made/up");
        awaitLog("S window/showMessageRequest");
        until(() -> serverLog().stream().filter(l -> l.startsWith("S ")).count() == 9);
        assertEquals("S workspace/configuration [null,null]", logLine("S workspace/configuration"));
        assertTrue(serverLog().contains("S workspace/configuration []"));
        assertTrue(logLine("S workspace/workspaceFolders").contains("\"name\":\"" + dir.toFile().getName() + "\""));
        assertTrue(logLine("S workspace/applyEdit").contains("\"applied\":false"));
        assertTrue(logLine("S made/up").contains("error Method not supported: made/up"));
        assertEquals("S client/registerCapability null", logLine("S client/registerCapability"));
        assertEquals("S window/workDoneProgress/create null", logLine("S window/workDoneProgress/create"));
        until(() -> latest.containsKey(URI.create("file:///other.java")));
        assertEquals("from probe", latest.get(URI.create("file:///other.java")).get(0).message());
        assertTrue(logs.contains("[java] log from server") && logs.contains("[java] show from server"), logs.toString());
        until(() -> logs.contains("[java] fake server starting"));
        assertTrue(diagnosticCalls.stream().noneMatch(u -> u.toString().contains("not")), "a malformed uri must be dropped, not forwarded");
    }

    @Test void sessionStateIsObservableAndStoppingIsIdempotent() throws Exception {
        LanguageServerSession session = new LanguageServerSession("java", command("full"), dir.toFile(), new Object(), List::of, (u, l) -> { }, logs::add, s -> { });
        assertEquals("java", session.languageId());
        assertTrue(session.command().contains("FakeLanguageServer"));
        assertFalse(session.isReady());
        assertFalse(session.isAlive());
        session.stopNow(); // never started: nothing to stop
        session = new LanguageServerSession("java", command("full"), dir.toFile(), new Object(), List::of, (u, l) -> { }, logs::add, s -> { });
        session.start();
        session.whenReady().get(30, TimeUnit.SECONDS);
        assertTrue(session.isReady());
        assertTrue(session.isAlive());
        session.stopNow();
        session.stopNow();
        assertFalse(session.isAlive());
    }

    @Test void aSessionWithoutARootStillInitializes() throws Exception {
        LanguageServerSession session = new LanguageServerSession("java", command("full"), null, new Object(), List::of, (u, l) -> { }, logs::add, s -> { });
        session.start();
        session.whenReady().get(30, TimeUnit.SECONDS);
        assertTrue(logLine("R initialize") != null);
        session.stopAsync();
        until(() -> !session.isAlive());
    }

    @Test void aServerThatIgnoresTerminationIsForceKilled() throws Exception {
        LanguageServerSession session = new LanguageServerSession("java", "trap '' TERM; " + command("stubborn"), dir.toFile(), new Object(), List::of, (u, l) -> { }, logs::add, s -> { });
        session.start();
        session.whenReady().get(30, TimeUnit.SECONDS);
        long begin = System.nanoTime();
        session.stopNow();
        assertFalse(session.isAlive());
        assertTrue((System.nanoTime() - begin) / 1_000_000 >= 1_500, "the session waited out its grace period before killing");
    }

    @Test void stoppingFromAnInterruptedThreadStillKillsTheServer() throws Exception {
        LanguageServerSession session = new LanguageServerSession("java", "trap '' TERM; " + command("stubborn"), dir.toFile(), new Object(), List::of, (u, l) -> { }, logs::add, s -> { });
        session.start();
        session.whenReady().get(30, TimeUnit.SECONDS);
        Thread.currentThread().interrupt();
        try { session.stopNow(); assertTrue(Thread.currentThread().isInterrupted()); } finally { Thread.interrupted(); }
        until(() -> !session.isAlive());
    }

    @Test void theExitCodeLookupGivesUpWhenInterrupted() throws Exception {
        LanguageServerSession session = new LanguageServerSession("java", command("full"), dir.toFile(), new Object(), List::of, (u, l) -> { }, logs::add, s -> { });
        assertEquals("", session.exitDescription(), "no process yet");
        session.start();
        session.whenReady().get(30, TimeUnit.SECONDS);
        Thread.currentThread().interrupt();
        try { assertEquals("", session.exitDescription()); assertTrue(Thread.currentThread().isInterrupted()); } finally { Thread.interrupted(); }
        session.stopNow();
    }
}
