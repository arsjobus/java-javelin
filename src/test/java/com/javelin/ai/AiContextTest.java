package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiContextTest {
    private static AiSource source(String text, int a, int b) { return new AiSource("src/Foo.java", "Foo.java", "java", text, a, b); }

    @Test void theSelectionIsWhatIsSent() throws Exception {
        AiContext c = AiContext.of(AiAction.EXPLAIN, source("abc\ndef\nghi", 4, 7), 100);
        assertEquals("def", c.text());
        assertEquals(AiContext.Scope.SELECTION, c.scope());
        assertEquals(4, c.start());
        assertEquals(7, c.end());
        assertFalse(c.truncated());
    }

    @Test void aSelectionIsRequiredWhereTheActionNeedsOne() {
        for (AiAction action : new AiAction[] {AiAction.EXPLAIN, AiAction.DEBUG, AiAction.PROPOSE_CHANGE}) {
            AiException ex = assertThrows(AiException.class, () -> AiContext.of(action, source("abc", 1, 1), 100));
            assertEquals(AiException.Kind.REFUSED, ex.kind());
            assertTrue(ex.getMessage().contains("Select"));
        }
    }

    @Test void generateTestsFallsBackToTheWholeFile() throws Exception {
        AiContext c = AiContext.of(AiAction.GENERATE_TESTS, source("class Foo {}", 0, 0), 100);
        assertEquals("class Foo {}", c.text());
        assertEquals(AiContext.Scope.WHOLE_FILE, c.scope());
    }

    @Test void aSelectionGivenBackwardsIsStillTheSameRange() throws Exception {
        assertEquals("def", AiContext.of(AiAction.EXPLAIN, source("abc\ndef\nghi", 7, 4), 100).text());
    }

    @Test void anEmptyDocumentHasNothingToSend() {
        assertEquals(AiException.Kind.REFUSED, assertThrows(AiException.class, () -> AiContext.of(AiAction.GENERATE_TESTS, source("", 0, 0), 100)).kind());
    }

    @Test void longTextIsCutAndMarkedForActionsThatOnlyRead() throws Exception {
        AiContext c = AiContext.of(AiAction.EXPLAIN, source("x".repeat(50), 0, 50), 20);
        assertEquals(20, c.text().length());
        assertTrue(c.truncated());
        assertEquals(50, c.totalChars());
        assertEquals(20, c.end());
    }

    @Test void aCutNeverSplitsACharacter() throws Exception {
        String text = "ab" + "\uD83D\uDE00".repeat(5); // 2 + 10 chars; the cut at 3 falls inside the first emoji
        AiContext c = AiContext.of(AiAction.EXPLAIN, source(text, 0, text.length()), 3);
        assertEquals("ab", c.text());
    }

    @Test void aChangeIsNeverProposedForTextTheModelOnlyPartlySaw() {
        AiException ex = assertThrows(AiException.class, () -> AiContext.of(AiAction.PROPOSE_CHANGE, source("x".repeat(50), 0, 50), 20));
        assertEquals(AiException.Kind.REFUSED, ex.kind());
        assertTrue(ex.getMessage().contains("ai.maxContextChars"));
    }

    @Test void aSourceClampsASelectionOutsideTheText() {
        AiSource s = source("abc", -5, 99);
        assertEquals(0, s.selectionStart());
        assertEquals(3, s.selectionEnd());
    }

    @Test void aChangeIsProposedForWholeLinesSoTheModelSeesTheIndentation() throws Exception {
        String text = "class A {\n    int x = 1;\n    int y = 2;\n}\n";
        int from = text.indexOf("int x"), to = text.indexOf("1;") + 2; // starts and ends in the middle of a line
        AiContext c = AiContext.of(AiAction.PROPOSE_CHANGE, source(text, from, to), 1000);
        assertEquals("    int x = 1;\n", c.text());
        assertEquals(text.indexOf("    int x"), c.start());
        assertEquals(text.indexOf("    int y"), c.end());
        assertEquals(AiContext.Scope.SELECTION, c.scope());
    }

    @Test void aSelectionThatAlreadyCoversWholeLinesIsNotWidened() throws Exception {
        String text = "a\nb\nc\n";
        AiContext c = AiContext.of(AiAction.PROPOSE_CHANGE, source(text, 2, 4), 100);
        assertEquals("b\n", c.text());
        assertEquals(2, c.start());
        assertEquals(4, c.end());
    }

    @Test void theLastLineWithoutALineBreakIsIncludedWhole() throws Exception {
        AiContext c = AiContext.of(AiAction.PROPOSE_CHANGE, source("a\n  b = 1", 5, 6), 100);
        assertEquals("  b = 1", c.text());
        assertEquals(9, c.end());
    }

    @Test void onlyAChangeIsWidenedNotWhatTheModelJustReads() throws Exception {
        assertEquals("de", AiContext.of(AiAction.EXPLAIN, source("abc\ndef\nghi", 4, 6), 100).text());
    }

    @Test void theLimitCountsTheWholeLinesOfAChange() {
        String text = "x".repeat(15) + "\n" + "y".repeat(15) + "\n";
        assertThrows(AiException.class, () -> AiContext.of(AiAction.PROPOSE_CHANGE, source(text, 10, 20), 20));
    }
}
