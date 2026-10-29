package com.javelin_ide.language;

import com.javelin_ide.build.ShellCommand;
import com.javelin_ide.build.ShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One running language server and the protocol conversation with it: handshake, document sync, and the
 * requests the editor makes. Started only on an explicit user action. Callbacks arrive on background threads.
 */
public final class LanguageServerSession {
    private static final long INITIALIZE_TIMEOUT_SECONDS = 60;

    private final String languageId;
    private final String command;
    private final File root;
    private final Object lock;
    private final Supplier<Collection<TextDocument>> openDocuments;
    private final BiConsumer<URI, List<Lsp.Diagnostic>> onDiagnostics;
    private final Consumer<String> onLog;
    private final Consumer<LanguageServerSession> onExit;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Map<URI, Integer> sentVersions = new ConcurrentHashMap<>();
    private Process process;
    private JsonRpc rpc;
    private Map<String, Object> capabilities = Map.of();
    private int syncKind = 1; // 0 none, 1 full, 2 incremental
    private boolean wantsSave;
    private boolean saveIncludesText;
    private volatile boolean stopping;

    LanguageServerSession(String languageId, String command, File root, Object lock, Supplier<Collection<TextDocument>> openDocuments,
                          BiConsumer<URI, List<Lsp.Diagnostic>> onDiagnostics, Consumer<String> onLog, Consumer<LanguageServerSession> onExit) {
        this.languageId = languageId; this.command = command; this.root = root; this.lock = lock;
        this.openDocuments = openDocuments; this.onDiagnostics = onDiagnostics; this.onLog = onLog; this.onExit = onExit;
    }

    public String languageId() { return languageId; }
    public String command() { return command; }
    public boolean isReady() { return ready.isDone() && !ready.isCompletedExceptionally(); }
    public boolean isAlive() { return process != null && process.isAlive() && !stopping; }
    public CompletableFuture<Void> whenReady() { return ready; }

    void start() throws IOException {
        ProcessBuilder builder = ShellEnvironment.apply(new ProcessBuilder(ShellCommand.wrap(command)));
        if (root != null) builder.directory(root);
        process = builder.start();
        rpc = new JsonRpc(process.getInputStream(), process.getOutputStream(), this::onNotification, this::onServerRequest, this::onConnectionClosed);
        Thread stderr = new Thread(this::pumpStderr, "language-server-stderr");
        stderr.setDaemon(true);
        stderr.start();
        rpc.start();
        rpc.request("initialize", initializeParams()).orTimeout(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, error) -> {
            if (error != null) {
                ready.completeExceptionally(error);
                onLog.accept("[" + languageId + "] initialize failed: " + message(error));
                stopAsync();
                return;
            }
            Object caps = Json.get(result, "capabilities");
            capabilities = Json.asObject(caps) == null ? Map.of() : Json.asObject(caps);
            readSyncOptions(capabilities.get("textDocumentSync"));
            rpc.notify("initialized", Map.of());
            synchronized (lock) {
                for (TextDocument doc : openDocuments.get()) sendOpen(doc);
                ready.complete(null);
            }
        });
    }

    private Map<String, Object> initializeParams() {
        URI rootUri = root == null ? null : root.toURI();
        Map<String, Object> textDocument = Json.obj(
            "synchronization", Json.obj("didSave", true),
            "completion", Json.obj("completionItem", Json.obj("snippetSupport", false)),
            "hover", Json.obj("contentFormat", List.of("plaintext", "markdown")),
            "definition", Json.obj("linkSupport", true),
            "references", Json.obj(),
            "rename", Json.obj("prepareSupport", false),
            "signatureHelp", Json.obj("signatureInformation", Json.obj("documentationFormat", List.of("plaintext", "markdown"),
                "parameterInformation", Json.obj("labelOffsetSupport", true), "activeParameterSupport", true)),
            "documentSymbol", Json.obj("hierarchicalDocumentSymbolSupport", true),
            "formatting", Json.obj(),
            "publishDiagnostics", Json.obj());
        // applyEdit stays off: edits proposed by a server are never applied without the user seeing them first.
        Map<String, Object> workspace = Json.obj("workspaceFolders", true, "configuration", false, "applyEdit", false);
        List<Object> folders = root == null ? List.of() : List.of(Json.obj("uri", rootUri.toString(), "name", root.getName()));
        return Json.obj("processId", ProcessHandle.current().pid(), "clientInfo", Json.obj("name", "javelin IDE"),
            "rootUri", rootUri == null ? null : rootUri.toString(), "workspaceFolders", root == null ? null : folders,
            "capabilities", Json.obj("textDocument", textDocument, "workspace", workspace));
    }

    private void readSyncOptions(Object sync) {
        if (sync instanceof Number n) { syncKind = n.intValue(); return; }
        if (Json.asObject(sync) == null) return;
        syncKind = Json.integer(sync, "change", 1);
        Object save = Json.get(sync, "save");
        wantsSave = save != null && !Boolean.FALSE.equals(save);
        saveIncludesText = Json.bool(save, "includeText");
    }

    // ---- document sync; the caller holds the service lock ----

    void attach(TextDocument doc) { if (isReady()) sendOpen(doc); }

    private void sendOpen(TextDocument doc) {
        sentVersions.put(doc.uri(), doc.version());
        rpc.notify("textDocument/didOpen", Json.obj("textDocument", Json.obj("uri", doc.uri().toString(), "languageId", doc.languageId(),
            "version", doc.version(), "text", doc.text())));
    }

    void changed(TextDocument doc, Lsp.Range replaced, String inserted) {
        if (!isReady() || !sentVersions.containsKey(doc.uri()) || syncKind == 0) return;
        sentVersions.put(doc.uri(), doc.version());
        Map<String, Object> change = syncKind == 2 ? Json.obj("range", replaced.toJson(), "text", inserted) : Json.obj("text", doc.text());
        rpc.notify("textDocument/didChange", Json.obj("textDocument", Json.obj("uri", doc.uri().toString(), "version", doc.version()), "contentChanges", List.of(change)));
    }

    /** The whole text was replaced; always sent as a full-document change. */
    void replaced(TextDocument doc) {
        if (!isReady() || !sentVersions.containsKey(doc.uri()) || syncKind == 0) return;
        sentVersions.put(doc.uri(), doc.version());
        rpc.notify("textDocument/didChange", Json.obj("textDocument", Json.obj("uri", doc.uri().toString(), "version", doc.version()),
            "contentChanges", List.of(Json.obj("text", doc.text()))));
    }

    void saved(TextDocument doc) {
        if (!isReady() || !sentVersions.containsKey(doc.uri()) || !wantsSave) return;
        Map<String, Object> params = Json.obj("textDocument", Json.obj("uri", doc.uri().toString()));
        if (saveIncludesText) params.put("text", doc.text());
        rpc.notify("textDocument/didSave", params);
    }

    void detach(URI uri) {
        if (sentVersions.remove(uri) == null || !isReady()) return;
        rpc.notify("textDocument/didClose", Json.obj("textDocument", Json.obj("uri", uri.toString())));
    }

    // ---- requests ----

    public CompletableFuture<List<Lsp.CompletionItem>> completion(URI uri, Lsp.Position position) {
        return query("completionProvider", "completion", "textDocument/completion", Lsp.positionParams(uri, position), Lsp::completions);
    }

    /** Hover text, or an empty string when the server has nothing to say at that position. */
    public CompletableFuture<String> hover(URI uri, Lsp.Position position) {
        return query("hoverProvider", "hover", "textDocument/hover", Lsp.positionParams(uri, position), Lsp::hoverText);
    }

    public CompletableFuture<List<Lsp.Location>> definition(URI uri, Lsp.Position position) {
        return query("definitionProvider", "go to definition", "textDocument/definition", Lsp.positionParams(uri, position), Lsp::locations);
    }

    public CompletableFuture<List<Lsp.Location>> references(URI uri, Lsp.Position position) {
        Map<String, Object> params = Lsp.positionParams(uri, position);
        params.put("context", Json.obj("includeDeclaration", true));
        return query("referencesProvider", "find references", "textDocument/references", params, Lsp::locations);
    }

    public CompletableFuture<Lsp.WorkspaceEdit> rename(URI uri, Lsp.Position position, String newName) {
        Map<String, Object> params = Lsp.positionParams(uri, position);
        params.put("newName", newName);
        return query("renameProvider", "rename", "textDocument/rename", params, Lsp::workspaceEdit);
    }

    /** Signature text for the call at the position, or an empty string when there is none. */
    public CompletableFuture<String> signatureHelp(URI uri, Lsp.Position position) {
        return query("signatureHelpProvider", "signature help", "textDocument/signatureHelp", Lsp.positionParams(uri, position), Lsp::signatureText);
    }

    public CompletableFuture<List<Lsp.Symbol>> documentSymbols(URI uri) {
        return query("documentSymbolProvider", "document symbols", "textDocument/documentSymbol", Json.obj("textDocument", Lsp.textDocument(uri)), Lsp::symbols);
    }

    /** Edits that format the whole document. They are proposals: the caller decides whether to apply them. */
    public CompletableFuture<List<Lsp.TextEdit>> formatting(URI uri, int tabSize, boolean insertSpaces) {
        Map<String, Object> params = Json.obj("textDocument", Lsp.textDocument(uri), "options", Json.obj("tabSize", tabSize, "insertSpaces", insertSpaces));
        return query("documentFormattingProvider", "formatting", "textDocument/formatting", params, Lsp::textEdits);
    }

    private <T> CompletableFuture<T> query(String capability, String feature, String method, Object params, Function<Object, T> convert) {
        return ready.thenCompose(v -> {
            Object supported = capabilities.get(capability);
            if (supported == null || Boolean.FALSE.equals(supported))
                return CompletableFuture.<T>failedFuture(new UnsupportedOperationException("The " + languageId + " language server does not support " + feature + "."));
            return rpc.request(method, params).thenApply(convert);
        });
    }

    // ---- incoming ----

    private void onNotification(String method, Object params) {
        switch (method) {
            case "textDocument/publishDiagnostics" -> {
                String uri = Json.string(params, "uri");
                if (uri == null) return;
                try { onDiagnostics.accept(URI.create(uri), Lsp.diagnostics(Json.get(params, "diagnostics"))); } catch (IllegalArgumentException ignored) { }
            }
            case "window/logMessage", "window/showMessage" -> { String text = Json.string(params, "message"); if (text != null) onLog.accept("[" + languageId + "] " + text); }
            default -> { /* progress, telemetry and the like are ignored on purpose */ }
        }
    }

    private Object onServerRequest(String method, Object params) {
        switch (method) {
            case "workspace/configuration" -> {
                List<Object> items = Json.asArray(Json.get(params, "items"));
                List<Object> answers = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.size(); i++) answers.add(null);
                return answers;
            }
            case "workspace/workspaceFolders" -> { return root == null ? null : List.of(Json.obj("uri", root.toURI().toString(), "name", root.getName())); }
            case "workspace/applyEdit" -> { return Json.obj("applied", false, "failureReason", "javelin IDE applies edits only when the user asks for them."); }
            case "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create", "window/showMessageRequest" -> { return null; }
            default -> throw new JsonRpc.RpcException(-32601, "Method not supported: " + method);
        }
    }

    private void pumpStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) onLog.accept("[" + languageId + "] " + line);
        } catch (IOException ignored) { }
    }

    private void onConnectionClosed(Throwable reason) {
        ready.completeExceptionally(new IllegalStateException("The " + languageId + " language server stopped."));
        if (!stopping) onLog.accept("[" + languageId + "] language server exited" + exitDescription());
        onExit.accept(this);
    }

    /** The connection usually closes a moment before the process is reapable, so give the exit code a short chance to appear. */
    String exitDescription() {
        if (process == null) return "";
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) return " with code " + process.exitValue();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return "";
    }

    // ---- shutdown ----

    /** Stops the server without blocking the caller. */
    public void stopAsync() {
        Thread t = new Thread(this::stopNow, "language-server-stop");
        t.setDaemon(true);
        t.start();
    }

    /** Asks the server to shut down politely, then makes sure the process and its children are gone. */
    public void stopNow() {
        if (stopping) return;
        stopping = true;
        if (process == null) return;
        if (isReady()) {
            try { rpc.request("shutdown", null).get(1500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); } // keep the flag; the wait below then fails fast and kills
            catch (Exception ignored) { /* an unresponsive or failing server is stopped below anyway */ }
            rpc.notify("exit", null);
        }
        rpc.close();
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) forceKill();
        } catch (InterruptedException e) {
            forceKill(); // being interrupted must never leave a server running
            Thread.currentThread().interrupt();
        }
    }

    private void forceKill() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String message(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
