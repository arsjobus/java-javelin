package com.javelin_ide.language;

import java.io.File;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** What a language server proposed for a rename, grouped by local file, ready to be shown for approval. JavaFX-free. */
public final class RenamePlan {
    private static final int SHOWN_FILES = 15;

    private final Map<File, List<Lsp.TextEdit>> byFile;
    private final int skipped;

    private RenamePlan(Map<File, List<Lsp.TextEdit>> byFile, int skipped) {
        this.byFile = byFile;
        this.skipped = skipped;
    }

    /** Groups the edits by file; edits whose URI is not a local file ({@code toFile} gives {@code null}) are counted as skipped. */
    public static RenamePlan of(Lsp.WorkspaceEdit edit, Function<URI, File> toFile) {
        Map<File, List<Lsp.TextEdit>> byFile = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<URI, List<Lsp.TextEdit>> e : edit.changes().entrySet()) {
            File file = toFile.apply(e.getKey());
            if (file == null) skipped += e.getValue().size();
            else byFile.put(file, e.getValue());
        }
        return new RenamePlan(byFile, skipped);
    }

    public Map<File, List<Lsp.TextEdit>> byFile() { return byFile; }

    public int skipped() { return skipped; }

    /** The number of edits that will be applied. */
    public int total() { return byFile.values().stream().mapToInt(List::size).sum(); }

    /** The text of the confirmation dialog: what changes and where, before anything is applied. */
    public String summary(String oldName, String newName, Function<File, String> display) {
        StringBuilder summary = new StringBuilder("Rename " + oldName + " to " + newName + ": " + total() + " edit(s) in " + byFile.size() + " file(s).\n\n");
        int shown = 0;
        for (Map.Entry<File, List<Lsp.TextEdit>> e : byFile.entrySet()) {
            if (shown++ == SHOWN_FILES) { summary.append("  ...\n"); break; }
            summary.append("  ").append(display.apply(e.getKey())).append("  (").append(e.getValue().size()).append(")\n");
        }
        if (skipped > 0) summary.append("\n").append(skipped).append(" edit(s) in non-file locations will be skipped.\n");
        summary.append("\nFiles that are not open will be opened in tabs. The changes stay unsaved, so you can review and undo them.");
        return summary.toString();
    }
}
