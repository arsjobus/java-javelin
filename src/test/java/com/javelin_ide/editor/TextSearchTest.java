package com.javelin_ide.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.editor.TextSearch.Match;
import org.junit.jupiter.api.Test;

class TextSearchTest {
    private static final String TEXT = "foo bar foo baz\nfoo";   // "foo" at 0, 8 and 16

    private static Match m(int start, int end) { return new Match(start, end); }

    // ---- first Find: from the caret, wrapping ----

    @Test void firstFindsTheMatchAtTheCaret() {
        assertEquals(m(8, 11), TextSearch.first(TEXT, "foo", 8));
    }

    @Test void firstFindsTheNextMatchAfterTheCaret() {
        assertEquals(m(8, 11), TextSearch.first(TEXT, "foo", 4));
    }

    @Test void firstWrapsToTheStartWhenNothingFollowsTheCaret() {
        assertEquals(m(0, 3), TextSearch.first(TEXT, "foo", 17));
    }

    @Test void firstReturnsNullWhenThereIsNoMatchOrNoQuery() {
        assertNull(TextSearch.first(TEXT, "nope", 0));
        assertNull(TextSearch.first(TEXT, "", 0));
    }

    @Test void firstToleratesACaretOutsideTheText() {
        assertEquals(m(0, 3), TextSearch.first(TEXT, "foo", 999));
        assertEquals(m(0, 3), TextSearch.first(TEXT, "foo", -5));
    }

    // ---- Find Next: the reported bug, "no way to go to the next found instance" ----

    @Test void nextMovesPastTheCurrentlySelectedMatch() {
        assertEquals(m(8, 11), TextSearch.next(TEXT, "foo", 0, 3));
        assertEquals(m(16, 19), TextSearch.next(TEXT, "foo", 8, 11));
    }

    @Test void nextWrapsAroundFromTheLastMatch() {
        assertEquals(m(0, 3), TextSearch.next(TEXT, "foo", 16, 19));
    }

    @Test void repeatedNextVisitsEveryMatchOnceAndThenRepeats() {
        Match at = TextSearch.first(TEXT, "foo", 0);
        int[] seen = new int[4];
        for (int i = 0; i < seen.length; i++) {
            seen[i] = at.start();
            at = TextSearch.next(TEXT, "foo", at.start(), at.end());
        }
        assertArrayEquals(new int[] {0, 8, 16, 0}, seen);
    }

    @Test void nextFromACaretStartsAtTheCaretItself() {
        assertEquals(m(8, 11), TextSearch.next(TEXT, "foo", 8, 8));
    }

    @Test void nextFromSomeOtherSelectionStartsAtThatSelection() {
        assertEquals(m(8, 11), TextSearch.next(TEXT, "foo", 8, 10));    // shorter than the query
        assertEquals(m(4, 7), TextSearch.next("foo bar bar", "bar", 4, 8));   // right start, wrong length: not a match, so stay
    }

    @Test void nextWithNoQueryOrNoMatchIsNull() {
        assertNull(TextSearch.next(TEXT, "", 0, 0));
        assertNull(TextSearch.next(TEXT, "nope", 0, 0));
    }

    @Test void nextWalksOverlappingMatchesOneCharacterAtATime() {
        assertEquals(m(1, 3), TextSearch.next("aaa", "aa", 0, 2));
        assertEquals(m(0, 2), TextSearch.next("aaa", "aa", 1, 3));
    }

    @Test void nextWithTheOnlyMatchSelectedStaysOnIt() {
        assertEquals(m(4, 7), TextSearch.next("foo bar baz", "bar", 4, 7));
    }

    // ---- Find Previous ----

    @Test void previousMovesBackwardsThroughTheMatches() {
        assertEquals(m(8, 11), TextSearch.previous(TEXT, "foo", 16));
        assertEquals(m(0, 3), TextSearch.previous(TEXT, "foo", 8));
    }

    @Test void previousWrapsToTheLastMatchFromTheFirst() {
        assertEquals(m(16, 19), TextSearch.previous(TEXT, "foo", 0));
    }

    @Test void previousFromACaretInsideTheTextFindsTheMatchBeforeIt() {
        assertEquals(m(0, 3), TextSearch.previous(TEXT, "foo", 5));
    }

    @Test void previousWalksOverlappingMatchesBackwards() {
        assertEquals(m(0, 2), TextSearch.previous("aaa", "aa", 1));
        assertEquals(m(1, 3), TextSearch.previous("aaa", "aa", 0));
    }

    @Test void previousWithNoQueryOrNoMatchIsNull() {
        assertNull(TextSearch.previous(TEXT, "", 5));
        assertNull(TextSearch.previous(TEXT, "nope", 5));
    }

    // ---- counting, for a "3 of 12" status message ----

    @Test void countCountsOverlappingMatches() {
        assertEquals(3, TextSearch.count(TEXT, "foo"));
        assertEquals(2, TextSearch.count("aaa", "aa"));
        assertEquals(0, TextSearch.count(TEXT, "nope"));
        assertEquals(0, TextSearch.count(TEXT, ""));
    }

    @Test void ordinalIsThePositionOfTheMatchAtAnOffset() {
        assertEquals(1, TextSearch.ordinal(TEXT, "foo", 0));
        assertEquals(2, TextSearch.ordinal(TEXT, "foo", 8));
        assertEquals(3, TextSearch.ordinal(TEXT, "foo", 16));
        assertEquals(2, TextSearch.ordinal("aaa", "aa", 1));
    }

    @Test void ordinalIsZeroWhereNoMatchStarts() {
        assertEquals(0, TextSearch.ordinal(TEXT, "foo", 1));
        assertEquals(0, TextSearch.ordinal(TEXT, "", 0));
        assertEquals(0, TextSearch.ordinal(TEXT, "foo", 999));
    }

    @Test void matchesAreCaseSensitive() {
        assertNull(TextSearch.first("Foo", "foo", 0));
        assertEquals(0, TextSearch.count("Foo", "foo"));
    }
    @Test void ignoreCaseFindsMatchesWithoutChangingTheirOffsets() {
        assertEquals(m(0, 3), TextSearch.first("Foo fOO", "foo", 0, true));
        assertEquals(m(4, 7), TextSearch.next("Foo fOO", "foo", 0, 3, true));
        assertEquals(m(0, 3), TextSearch.previous("Foo fOO", "foo", 4, true));
        assertEquals(2, TextSearch.count("Foo fOO", "foo", true));
        assertEquals(2, TextSearch.ordinal("Foo fOO", "foo", 4, true));
    }

    @Test void replacementIsCaseSensitiveByDefaultAndCanIgnoreCase() {
        assertEquals("foo X X", TextSearch.replace("foo X Foo", "Foo", "X", false));
        assertEquals("X X X", TextSearch.replace("foo X Foo", "foo", "X", true));
        assertEquals("foo", TextSearch.replace("foo", "", "X", true));
    }

}

