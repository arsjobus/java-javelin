package com.javelin_ide.language;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text handling of language-server edits, shared by the editor and the tests. JavaFX-free. Lines are separated by
 * {@code \n}, matching the editor's paragraphs and {@link TextDocument}.
 */
public final class TextEdits {
    private TextEdits() {}

    /** A replacement of {@code [start, end)} with {@code text}. */
    public record Change(int start, int end, String text) {}

    /** The offset of a line/character position, with a line past the end or a column past the line end clamped to it. */
    public static int offset(String text, Lsp.Position position) {
        int lineStart = 0;
        int line = 0;
        int target = Math.max(0, position.line());
        while (line < target) {
            int next = text.indexOf('\n', lineStart);
            if (next < 0) break; // fewer lines than asked for: stay on the last one
            lineStart = next + 1;
            line++;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        return lineStart + Math.max(0, Math.min(position.character(), lineEnd - lineStart));
    }

    /**
     * Turns edits that all refer to the original text into absolute changes, ordered from the bottom up so that applying
     * them one after another keeps earlier offsets valid. Inserts at the same position end up in the order of the list,
     * as the protocol requires; an end before its start is treated as an insert.
     */
    public static List<Change> resolve(String text, List<Lsp.TextEdit> edits) {
        List<int[]> spans = new ArrayList<>(); // {start, end, index in edits}
        for (int i = 0; i < edits.size(); i++) {
            int start = offset(text, edits.get(i).range().start());
            int end = Math.max(start, offset(text, edits.get(i).range().end()));
            spans.add(new int[] {start, end, i});
        }
        spans.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(b[2], a[2]));
        List<Change> changes = new ArrayList<>();
        for (int[] span : spans) changes.add(new Change(span[0], span[1], edits.get(span[2]).newText()));
        return changes;
    }

    /** The text with all edits applied (see {@link #resolve}). */
    public static String apply(String text, List<Lsp.TextEdit> edits) {
        StringBuilder out = new StringBuilder(text);
        for (Change c : resolve(text, edits)) out.replace(c.start(), c.end(), c.text());
        return out.toString();
    }

    /**
     * The smallest change that turns {@code old} into {@code replacement}: identical leading and trailing text is left
     * alone, so whole-file edits stay small. The result is relative to the start of {@code old}; {@code null} means the
     * two are already the same.
     */
    public static Change minimal(String old, String replacement) {
        int prefix = 0;
        int max = Math.min(old.length(), replacement.length());
        while (prefix < max && old.charAt(prefix) == replacement.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix && old.charAt(old.length() - 1 - suffix) == replacement.charAt(replacement.length() - 1 - suffix)) suffix++;
        if (prefix == old.length() && prefix == replacement.length()) return null;
        return new Change(prefix, old.length() - suffix, replacement.substring(prefix, replacement.length() - suffix));
    }
}
