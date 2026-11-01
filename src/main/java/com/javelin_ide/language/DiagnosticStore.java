package com.javelin_ide.language;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The diagnostics a language server last published, per file. Empty lists are never kept, and each edit moves the
 * stored ranges along with the text (see {@link DiagnosticShifter}) so underlines stay put until the server answers.
 * JavaFX-free: the editor reads and writes it, the language code fills it.
 */
public final class DiagnosticStore {
    private final Map<File, List<Lsp.Diagnostic>> byFile = new HashMap<>();

    /** The diagnostics for {@code file}, or {@code null} when it has none (also for a {@code null} file). */
    public List<Lsp.Diagnostic> get(File file) {
        return byFile.get(file);
    }

    /** Replaces what is stored for {@code file}; an empty list removes the entry. */
    public void replace(File file, List<Lsp.Diagnostic> reported) {
        if (reported.isEmpty()) byFile.remove(file);
        else byFile.put(file, reported);
    }

    /** Moves the stored ranges along with an edit that replaced {@code removed} with {@code inserted} at {@code start}. */
    public void shift(File file, Lsp.Position start, String removed, String inserted) {
        List<Lsp.Diagnostic> reported = byFile.get(file);
        if (reported == null) return;
        replace(file, DiagnosticShifter.shift(reported, start, removed, inserted));
    }
}
