package com.javelin_ide.editor;

import java.util.OptionalInt;
import java.util.function.IntUnaryOperator;

/** The arithmetic behind Go to Line and "open this file at line:column" (from Problems and References), without JavaFX. */
public final class LineNavigation {
    private LineNavigation() {}

    /** A caret position as the editor wants it: zero-based paragraph (line) and column. */
    public record Position(int paragraph, int column) {}

    /**
     * The one-based line number typed into the Go to Line dialog. Surrounding spaces are ignored; anything that is not a
     * whole number from 1 to {@code lineCount} (text, a decimal, zero, a line past the end, an overflowing number) is empty.
     */
    public static OptionalInt parseLine(String input, int lineCount) {
        if (input == null) return OptionalInt.empty();
        try {
            int line = Integer.parseInt(input.trim());
            return line >= 1 && line <= lineCount ? OptionalInt.of(line) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Turns a one-based line and column (as reported by compilers and language servers, which can be stale or wrong) into
     * a position that exists: a line outside the document becomes the nearest line, a column outside the line the nearest column.
     */
    public static Position clamp(int line, int column, int lineCount, IntUnaryOperator lineLength) {
        int paragraph = Math.max(0, Math.min(line - 1, lineCount - 1));
        return new Position(paragraph, Math.min(Math.max(0, column - 1), lineLength.applyAsInt(paragraph)));
    }
}
