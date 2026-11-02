package com.javelin.language;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Language intelligence for the editor. Tracks the text of open files and routes it to whichever language
 * servers the user has started. Nothing is started automatically: with no server running, this class only
 * keeps its copy of the open documents. The UI is a client of this class and never speaks the protocol itself.
 * Callbacks arrive on background threads.
 */
public final class LanguageService {
    private final Object lock = new Object();
    private final Map<URI, TextDocument> documents = new LinkedHashMap<>();
    private final Map<String, LanguageServerSession> sessions = new LinkedHashMap<>();
    private final Map<String, Set<URI>> diagnosed = new LinkedHashMap<>();
    private final BiConsumer<URI, List<Lsp.Diagnostic>> onDiagnostics;
    private final Consumer<String> onLog;
    private final Runnable onSessionsChanged;

    public LanguageService(BiConsumer<URI, List<Lsp.Diagnostic>> onDiagnostics, Consumer<String> onLog, Runnable onSessionsChanged) {
        this.onDiagnostics = onDiagnostics; this.onLog = onLog; this.onSessionsChanged = onSessionsChanged;
    }

    // ---- servers ----

    /**
     * Starts a server for a language. The command runs through the platform shell in the project directory.
     * The returned future completes when the server has finished its handshake, or fails if it never does.
     */
    public CompletableFuture<Void> start(String languageId, String command, File root) throws IOException {
        LanguageServerSession session;
        synchronized (lock) {
            if (sessions.containsKey(languageId)) throw new IOException("A " + languageId + " language server is already running.");
            session = new LanguageServerSession(languageId, command, root, lock, () -> documentsFor(languageId),
                (uri, list) -> publish(languageId, uri, list), onLog, this::sessionEnded);
            sessions.put(languageId, session);
        }
        try { session.start(); }
        catch (IOException ex) { synchronized (lock) { sessions.remove(languageId); } throw ex; }
        onSessionsChanged.run();
        return session.whenReady();
    }

    public void stop(String languageId) {
        LanguageServerSession session;
        synchronized (lock) { session = sessions.get(languageId); }
        if (session != null) session.stopAsync();
    }

    /** Blocks briefly while servers shut down; for application exit. */
    public void stopAll() {
        List<LanguageServerSession> all;
        synchronized (lock) { all = new ArrayList<>(sessions.values()); }
        all.forEach(LanguageServerSession::stopNow);
    }

    public boolean isRunning(String languageId) { synchronized (lock) { return sessions.containsKey(languageId); } }

    public List<String> runningLanguages() { synchronized (lock) { return new ArrayList<>(sessions.keySet()); } }

    private void sessionEnded(LanguageServerSession session) {
        Set<URI> stale;
        synchronized (lock) {
            sessions.remove(session.languageId(), session);
            stale = diagnosed.remove(session.languageId());
        }
        if (stale != null) for (URI uri : stale) onDiagnostics.accept(uri, List.of());
        onSessionsChanged.run();
    }

    private void publish(String languageId, URI uri, List<Lsp.Diagnostic> list) {
        synchronized (lock) {
            Set<URI> uris = diagnosed.computeIfAbsent(languageId, k -> new HashSet<>());
            if (list.isEmpty()) uris.remove(uri); else uris.add(uri);
        }
        onDiagnostics.accept(uri, list);
    }

    private List<TextDocument> documentsFor(String languageId) {
        // Called with the lock held by the session's ready callback.
        List<TextDocument> out = new ArrayList<>();
        for (TextDocument doc : documents.values()) if (doc.languageId().equals(languageId)) out.add(doc);
        return out;
    }

    // ---- document tracking ----

    /** Registers an open file. Files with an unrecognized extension are ignored. */
    public void open(File file, String text) {
        String languageId = Languages.idFor(file);
        if (languageId == null) return;
        synchronized (lock) {
            URI uri = file.toURI();
            if (documents.containsKey(uri)) return;
            TextDocument doc = new TextDocument(uri, languageId, text);
            documents.put(uri, doc);
            LanguageServerSession session = sessions.get(languageId);
            if (session != null) session.attach(doc);
        }
    }

    /** Reports one editor change: {@code removedLength} characters at {@code offset} were replaced by {@code inserted}. */
    public void change(File file, int offset, int removedLength, String inserted) {
        synchronized (lock) {
            TextDocument doc = documents.get(file.toURI());
            if (doc == null) return;
            Lsp.Range replaced = doc.replace(offset, removedLength, inserted);
            LanguageServerSession session = sessions.get(doc.languageId());
            if (session != null) session.changed(doc, replaced, inserted);
        }
    }

    /** The file was written to disk. Also opens it if it was untitled or newly renamed, and repairs any drift from the editor's text. */
    public void saved(File file, String editorText) {
        String languageId = Languages.idFor(file);
        if (languageId == null) return;
        synchronized (lock) {
            TextDocument doc = documents.get(file.toURI());
            if (doc == null) { open(file, editorText); doc = documents.get(file.toURI()); }
            else if (!doc.matches(editorText)) {
                doc.reset(editorText);
                LanguageServerSession session = sessions.get(languageId);
                if (session != null) session.replaced(doc);
            }
            LanguageServerSession session = sessions.get(languageId);
            if (session != null && doc != null) session.saved(doc);
        }
    }

    public void close(File file) {
        if (file == null) return;
        synchronized (lock) {
            TextDocument doc = documents.remove(file.toURI());
            if (doc == null) return;
            LanguageServerSession session = sessions.get(doc.languageId());
            if (session != null) session.detach(doc.uri());
        }
    }

    // ---- requests, all keyed by the offset in the editor's current text ----

    public CompletableFuture<List<Lsp.CompletionItem>> completion(File file, int offset) {
        return withPosition(file, offset, (s, uri, pos) -> s.completion(uri, pos));
    }

    public CompletableFuture<String> hover(File file, int offset) {
        return withPosition(file, offset, (s, uri, pos) -> s.hover(uri, pos));
    }

    public CompletableFuture<List<Lsp.Location>> definition(File file, int offset) {
        return withPosition(file, offset, (s, uri, pos) -> s.definition(uri, pos));
    }

    public CompletableFuture<List<Lsp.Location>> references(File file, int offset) {
        return withPosition(file, offset, (s, uri, pos) -> s.references(uri, pos));
    }

    public CompletableFuture<Lsp.WorkspaceEdit> rename(File file, int offset, String newName) {
        return withPosition(file, offset, (s, uri, pos) -> s.rename(uri, pos, newName));
    }

    public CompletableFuture<String> signatureHelp(File file, int offset) {
        return withPosition(file, offset, (s, uri, pos) -> s.signatureHelp(uri, pos));
    }

    public CompletableFuture<List<Lsp.Symbol>> documentSymbols(File file) {
        return withPosition(file, 0, (s, uri, pos) -> s.documentSymbols(uri));
    }

    public CompletableFuture<List<Lsp.TextEdit>> formatting(File file, int tabSize, boolean insertSpaces) {
        return withPosition(file, 0, (s, uri, pos) -> s.formatting(uri, tabSize, insertSpaces));
    }

    private interface Query<T> { CompletableFuture<T> run(LanguageServerSession session, URI uri, Lsp.Position position); }

    private <T> CompletableFuture<T> withPosition(File file, int offset, Query<T> query) {
        synchronized (lock) {
            TextDocument doc = documents.get(file.toURI());
            if (doc == null) return CompletableFuture.failedFuture(new IllegalStateException("Language features need a saved file with a recognized extension."));
            LanguageServerSession session = sessions.get(doc.languageId());
            if (session == null) return CompletableFuture.failedFuture(new IllegalStateException("No " + doc.languageId() + " language server is running. Start one from the Language menu."));
            return query.run(session, doc.uri(), doc.positionAt(offset));
        }
    }
}
