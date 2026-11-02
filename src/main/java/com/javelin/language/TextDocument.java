package com.javelin.language;

import java.net.URI;

/**
 * The language service's copy of an open file's text, kept in step with the editor so that edits can be
 * reported to a language server as precise ranges. Lines are separated by {@code \n}, matching the editor's paragraphs.
 * All access goes through {@link LanguageService}, which serializes it.
 */
final class TextDocument {
    private final URI uri;
    private final String languageId;
    private final StringBuilder text;
    private int version = 1;

    TextDocument(URI uri, String languageId, String initial) { this.uri = uri; this.languageId = languageId; this.text = new StringBuilder(initial); }

    URI uri() { return uri; }
    String languageId() { return languageId; }
    int version() { return version; }
    String text() { return text.toString(); }

    /** Replaces the whole text (used when the editor and this copy have drifted apart, e.g. after a save). */
    void reset(String newText) { text.setLength(0); text.append(newText); version++; }

    boolean matches(String other) { return text.length() == other.length() && text.compareTo(new StringBuilder(other)) == 0; }

    Lsp.Position positionAt(int offset) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int line = 0, lineStart = 0;
        for (int i = 0; i < clamped; i++) if (text.charAt(i) == '\n') { line++; lineStart = i + 1; }
        return new Lsp.Position(line, clamped - lineStart);
    }

    /** Applies a replacement and returns the range it covered in the text as it was before the change. */
    Lsp.Range replace(int offset, int removedLength, String inserted) {
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = Math.max(start, Math.min(start + removedLength, text.length()));
        Lsp.Range range = new Lsp.Range(positionAt(start), positionAt(end));
        text.replace(start, end, inserted);
        version++;
        return range;
    }
}
