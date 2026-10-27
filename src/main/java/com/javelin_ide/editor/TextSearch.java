package com.javelin_ide.editor;

/**
 * Find / Find Next / Find Previous and literal replacement helpers, so they can be tested without JavaFX.
 *
 * <p>Matching is literal and case-sensitive unless ignoreCase is requested. Matches may overlap ({@code "aa"} matches twice in {@code "aaa"}).
 * Searching wraps around the document, and an empty query never matches.
 */
public final class TextSearch {
    private TextSearch() {}

    /** A match, as the {@code [start, end)} range to select. */
    public record Match(int start, int end) {}

    /**
     * The first match starting at or after {@code from}, wrapping to the start of the text; null when there is none.
     * This is what the first Find does, starting at the caret.
     */
    public static Match first(String text, String query, int from) {
        return first(text, query, from, false);
    }

    public static Match first(String text, String query, int from, boolean ignoreCase) {
        if (query.isEmpty()) return null;
        int at = indexOf(text, query, Math.max(0, Math.min(from, text.length())), ignoreCase);
        if (at < 0) at = indexOf(text, query, 0, ignoreCase);
        return at < 0 ? null : new Match(at, at + query.length());
    }

    private static int indexOf(String text, String query, int from, boolean ignoreCase) {
        if (!ignoreCase) return text.indexOf(query, from);
        for (int at = from; at <= text.length() - query.length(); at++)
            if (text.regionMatches(true, at, query, 0, query.length())) return at;
        return -1;
    }

    /**
     * The match after the current selection, wrapping to the start of the text. When the selection is itself a
     * match the search moves past it; otherwise (a caret, or some other selected text) it starts at the selection.
     */
    public static Match next(String text, String query, int selStart, int selEnd) {
        return next(text, query, selStart, selEnd, false);
    }

    public static Match next(String text, String query, int selStart, int selEnd, boolean ignoreCase) {
        return first(text, query, isMatchAt(text, query, selStart, selEnd, ignoreCase) ? selStart + 1 : selStart, ignoreCase);
    }

    /**
     * The match before the current selection, wrapping to the end of the text; null when there is none. The
     * match found always starts before {@code selStart}, so repeating it walks backwards through the document.
     */
    public static Match previous(String text, String query, int selStart) {
        return previous(text, query, selStart, false);
    }

    public static Match previous(String text, String query, int selStart, boolean ignoreCase) {
        if (query.isEmpty()) return null;
        int at = selStart <= 0 ? -1 : lastIndexOf(text, query, selStart - 1, ignoreCase);
        if (at < 0) at = lastIndexOf(text, query, text.length() - query.length(), ignoreCase);
        return at < 0 ? null : new Match(at, at + query.length());
    }

    private static int lastIndexOf(String text, String query, int from, boolean ignoreCase) {
        if (!ignoreCase) return text.lastIndexOf(query, from);
        for (int at = Math.min(from, text.length() - query.length()); at >= 0; at--)
            if (text.regionMatches(true, at, query, 0, query.length())) return at;
        return -1;
    }

    /** How many matches the text holds. */
    public static int count(String text, String query) {
        return count(text, query, false);
    }

    public static int count(String text, String query, boolean ignoreCase) {
        if (query.isEmpty()) return 0;
        int n = 0;
        for (int at = indexOf(text, query, 0, ignoreCase); at >= 0; at = indexOf(text, query, at + 1, ignoreCase)) n++;
        return n;
    }

    /** The 1-based position of the match that starts at {@code start}, or 0 when no match starts there. */
    public static int ordinal(String text, String query, int start) {
        return ordinal(text, query, start, false);
    }

    public static int ordinal(String text, String query, int start, boolean ignoreCase) {
        if (query.isEmpty() || start < 0 || start + query.length() > text.length()
                || !text.regionMatches(ignoreCase, start, query, 0, query.length())) return 0;
        int n = 0;
        for (int at = indexOf(text, query, 0, ignoreCase); at >= 0 && at <= start; at = indexOf(text, query, at + 1, ignoreCase)) n++;
        return n;
    }

    /** Replaces literal matches, optionally ignoring case, without treating replacement text as a regex. */
    public static String replace(String text, String query, String replacement, boolean ignoreCase) {
        if (query.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int copiedFrom = 0;
        for (int at = indexOf(text, query, 0, ignoreCase); at >= 0; at = indexOf(text, query, at + query.length(), ignoreCase)) {
            out.append(text, copiedFrom, at).append(replacement);
            copiedFrom = at + query.length();
        }
        return copiedFrom == 0 ? text : out.append(text, copiedFrom, text.length()).toString();
    }

    private static boolean isMatchAt(String text, String query, int selStart, int selEnd, boolean ignoreCase) {
        return !query.isEmpty() && selStart >= 0 && selEnd <= text.length() && selEnd - selStart == query.length()
                && text.regionMatches(ignoreCase, selStart, query, 0, query.length());
    }
}
