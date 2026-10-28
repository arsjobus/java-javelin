package com.javelin_ide.editor;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Bracket matching for Go to > Matching Bracket, so it can be tested without JavaFX.
 *
 * <p>Only {@code ()}, {@code []} and {@code {}} are matched, each kind on its own: {@code ( ]} does not pair. The scan is
 * purely textual, so a bracket inside a string literal or a comment counts like any other.
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

    /** The partner of the bracket at {@code index}; empty when that is not a bracket or it has no partner. */
    public static Optional<Pair> pair(String text, int index) {
        if (index < 0 || index >= text.length()) return Optional.empty();
        char c = text.charAt(index);
        int opening = OPENING.indexOf(c), closing = CLOSING.indexOf(c);
        if (opening < 0 && closing < 0) return Optional.empty();
        char open = opening >= 0 ? c : OPENING.charAt(closing);
        char close = opening >= 0 ? CLOSING.charAt(opening) : c;
        int step = opening >= 0 ? 1 : -1, depth = 0;
        for (int i = index; i >= 0 && i < text.length(); i += step) {
            char x = text.charAt(i);
            if (x == open) depth += step;
            else if (x == close) depth -= step;
            if (depth == 0) return Optional.of(new Pair(index, i));
        }
        return Optional.empty();
    }

    private static boolean isBracket(char c) { return OPENING.indexOf(c) >= 0 || CLOSING.indexOf(c) >= 0; }
}
