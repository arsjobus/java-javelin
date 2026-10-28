package com.javelin_ide.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class LineNavigationTest {
    @Test void aWholeNumberInsideTheDocumentIsAccepted() {
        assertEquals(OptionalInt.of(1), LineNavigation.parseLine("1", 10));
        assertEquals(OptionalInt.of(10), LineNavigation.parseLine("10", 10));
        assertEquals(OptionalInt.of(7), LineNavigation.parseLine("  7\t", 10), "surrounding spaces are ignored");
        assertEquals(OptionalInt.of(3), LineNavigation.parseLine("+3", 10), "what Integer.parseInt has always accepted");
    }

    @Test void anythingElseIsRejected() {
        for (String input : new String[] {"", "  ", "abc", "3.5", "1 2", "0", "-1", "11", "99999999999"})
            assertTrue(LineNavigation.parseLine(input, 10).isEmpty(), "\"" + input + "\"");
        assertTrue(LineNavigation.parseLine(null, 10).isEmpty());
        assertTrue(LineNavigation.parseLine("1", 0).isEmpty(), "a document with no lines has nowhere to go");
    }

    @Test void aPositionThatExistsIsConvertedToZeroBased() {
        assertEquals(new LineNavigation.Position(0, 0), LineNavigation.clamp(1, 1, 5, line -> 20));
        assertEquals(new LineNavigation.Position(2, 4), LineNavigation.clamp(3, 5, 5, line -> 20));
    }

    @Test void aLineOutsideTheDocumentBecomesTheNearestLine() {
        assertEquals(0, LineNavigation.clamp(0, 1, 5, line -> 20).paragraph(), "line 0");
        assertEquals(0, LineNavigation.clamp(-8, 1, 5, line -> 20).paragraph());
        assertEquals(4, LineNavigation.clamp(99, 1, 5, line -> 20).paragraph(), "past the end: the last line");
    }

    @Test void aColumnOutsideTheLineBecomesTheNearestColumn() {
        assertEquals(0, LineNavigation.clamp(1, 0, 5, line -> 20).column(), "column 0");
        assertEquals(0, LineNavigation.clamp(1, -3, 5, line -> 20).column());
        assertEquals(6, LineNavigation.clamp(2, 50, 5, line -> line == 1 ? 6 : 20).column(), "the end of that line, whatever other lines hold");
        assertEquals(0, LineNavigation.clamp(1, 5, 5, line -> 0).column(), "an empty line");
    }

    @Test void theLineLengthIsAskedForTheClampedLine() {
        assertEquals(new LineNavigation.Position(4, 3), LineNavigation.clamp(99, 99, 5, line -> line == 4 ? 3 : 100));
    }
}
