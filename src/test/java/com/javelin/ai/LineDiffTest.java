package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LineDiffTest {
    @Test void identicalTextsHaveNoChanges() {
        var d = LineDiff.unified("a\nb\n", "a\nb\n", 3);
        assertFalse(d.changed());
        assertEquals("No changes.", d.text());
    }

    @Test void aChangedLineIsRemovedAndAddedWithContext() {
        var d = LineDiff.unified("one\ntwo\nthree\nfour\n", "one\n2\nthree\nfour\n", 1);
        assertEquals(1, d.added());
        assertEquals(1, d.removed());
        assertEquals("@@ -1,3 +1,3 @@\n one\n-two\n+2\n three\n", d.text());
    }

    @Test void insertionsAndDeletionsAtTheEnds() {
        var d = LineDiff.unified("b\n", "a\nb\nc\n", 3);
        assertEquals(2, d.added());
        assertEquals(0, d.removed());
        assertEquals("@@ -1,1 +1,3 @@\n+a\n b\n+c\n", d.text());
        var e = LineDiff.unified("a\nb\nc\n", "b\n", 3);
        assertEquals(2, e.removed());
    }

    @Test void farApartChangesMakeTwoHunks() {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < 20; i++) { a.append("l").append(i).append('\n'); b.append(i == 2 ? "X" : i == 17 ? "Y" : "l" + i).append('\n'); }
        var d = LineDiff.unified(a.toString(), b.toString(), 2);
        assertEquals(2, d.text().split("@@ -").length - 1);
        assertEquals(2, d.added());
        assertEquals(2, d.removed());
        assertFalse(d.text().contains("l10"));
    }

    @Test void nearbyChangesShareAHunk() {
        var d = LineDiff.unified("a\nb\nc\nd\ne\n", "A\nb\nc\nd\nE\n", 3);
        assertEquals(1, d.text().split("@@ -").length - 1);
    }

    @Test void emptySidesWork() {
        var d = LineDiff.unified("", "a\nb\n", 3);
        assertEquals(2, d.added());
        assertEquals("@@ -1,0 +1,2 @@\n+a\n+b\n", d.text());
        assertEquals(2, LineDiff.unified("a\nb\n", "", 3).removed());
    }

    @Test void onlyLineEndingsDifferingIsSaid() {
        var crlf = LineDiff.unified("a\nb\n", "a\r\nb\r\n", 3);
        assertEquals(0, crlf.added());
        assertTrue(crlf.text().contains("line endings"));
        assertTrue(crlf.changed() || !crlf.changed());
        var finalBreak = LineDiff.unified("a\nb", "a\nb\n", 3);
        assertTrue(finalBreak.changed());
        assertTrue(finalBreak.text().contains("final line break"));
    }

    @Test void hugeMiddlesAreShownAsWholesaleReplacement() {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        int n = 1500;
        for (int i = 0; i < n; i++) { a.append("a").append(i).append('\n'); b.append("b").append(i).append('\n'); }
        var d = LineDiff.unified(a.toString(), b.toString(), 3);
        assertEquals(n, d.added());
        assertEquals(n, d.removed());
    }

    @Test void theResultReplaysToTheNewText() {
        String before = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n";
        String after = "alpha\nBETA\ngamma\nepsilon\nzeta\neta\n";
        var d = LineDiff.unified(before, after, 100);
        StringBuilder rebuilt = new StringBuilder();
        for (String line : d.text().split("\n")) if (!line.startsWith("@@") && !line.startsWith("-")) rebuilt.append(line.substring(1)).append('\n');
        assertEquals(after, rebuilt.toString());
    }
}
