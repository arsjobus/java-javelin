package com.javelin.editor;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Bracket matching for Go to > Matching Bracket, so it can be tested without JavaFX.
 *
 * <p>Only {@code ()}, {@code []} and {@code {}} are matched, each kind on its own: {@code ( ]} does not pair.
 *
 * <p>{@link #pair(String, int)} is purely textual, so a bracket inside a string literal or a comment counts like any
 * other. {@link #pair(String, int, List)} is told where the strings and comments are (the spans of
 * {@link SyntaxHighlighter}) and only pairs brackets of the same kind of region: a bracket in code pairs with code, a
 * bracket inside one string or comment pairs with the other brackets of that same string or comment.
 */
public final class Brackets {
    private static final String OPENING = "([{", CLOSING = ")]}";

    private Brackets() {}

    /** A bracket and the one it pairs with, as offsets into the text. */
    public record Pair(int bracket, int partner) {
        /** The first offset of the range from one bracket to the other. */
        public int start() { return Math.min(bracket, partner); }
        /** The offset just past the later of the two brackets: {@code [start, end)} is what to select. */
        public int end() { return Math.max(bracket, partner) + 1; }
    }

    /** The offset of the bracket the caret is on: the character after the caret wins over the one before it. */
    public static OptionalInt near(String text, int caret) {
        if (caret >= 0 && caret < text.length() && isBracket(text.charAt(caret))) return OptionalInt.of(caret);
        if (caret > 0 && caret <= text.length() && isBracket(text.charAt(caret - 1))) return OptionalInt.of(caret - 1);
        return OptionalInt.empty();
    }

    /** The partner of the bracket at {@code index}; empty when that is not a bracket or it has no partner. Counts every bracket. */
    public static Optional<Pair> pair(String text, int index) { return pair(text, index, List.of()); }

    /**
     * Like {@link #pair(String, int)}, but brackets inside a {@code "string"} or {@code "comment"} span are not
     * counted by a bracket in code, and a bracket inside such a span only pairs within that same span. Spans must be
     * in order and without overlaps, as {@link SyntaxHighlighter#spans(String, java.io.File)} returns them; with no
     * spans this is the textual match.
     */
    public static Optional<Pair> pair(String text, int index, List<SyntaxHighlighter.Span> spans) {
        if (index < 0 || index >= text.length()) return Optional.empty();
        char c = text.charAt(index);
        int opening = OPENING.indexOf(c), closing = CLOSING.indexOf(c);
        if (opening < 0 && closing < 0) return Optional.empty();
        char open = opening >= 0 ? c : OPENING.charAt(closing);
        char close = opening >= 0 ? CLOSING.charAt(opening) : c;
        int step = opening >= 0 ? 1 : -1, depth = 0;
        int region = regionAt(spans, index);
        for (int i = index; i >= 0 && i < text.length(); i += step) {
            char x = text.charAt(i);
            if (x != open && x != close) continue;
            if (i != index && regionAt(spans, i) != region) continue;
            if (x == open) depth += step;
            else depth -= step;
            if (depth == 0) return Optional.of(new Pair(index, i));
        }
        return Optional.empty();
    }

    /** The index of the string or comment span holding {@code offset}, or -1 for code. */
    private static int regionAt(List<SyntaxHighlighter.Span> spans, int offset) {
        if (spans.isEmpty()) return -1;
        int lo = 0, hi = spans.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            SyntaxHighlighter.Span s = spans.get(mid);
            if (offset < s.start()) hi = mid - 1;
            else if (offset >= s.end()) lo = mid + 1;
            else return isLiteralOrComment(s) ? mid : -1;
        }
        return -1;
    }

    private static boolean isLiteralOrComment(SyntaxHighlighter.Span span) {
        return "string".equals(span.style()) || "comment".equals(span.style());
    }

    private static boolean isBracket(char c) { return OPENING.indexOf(c) >= 0 || CLOSING.indexOf(c) >= 0; }
}
