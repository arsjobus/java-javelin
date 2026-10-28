package com.javelin_ide.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BracketsTest {
    //                           012345678901
    private static final String TEXT = "f(a[1], {b})";

    @Test void theCharacterAfterTheCaretWinsOverTheOneBefore() {
        assertEquals(OptionalInt.of(1), Brackets.near(TEXT, 1), "caret before (");
        assertEquals(OptionalInt.of(1), Brackets.near(TEXT, 2), "caret after (");
        assertEquals(OptionalInt.of(2), Brackets.near("x[]", 2), "between [ and ]: the ] after the caret, not the [ before it");
    }

    @Test void noBracketNextToTheCaretMeansEmpty() {
        assertTrue(Brackets.near(TEXT, 7).isEmpty(), "the caret is between a comma and a space");
    }

    @Test void edgesOfTheTextAreSafe() {
        assertTrue(Brackets.near("", 0).isEmpty());
        assertTrue(Brackets.near("abc", 0).isEmpty());
        assertTrue(Brackets.near("abc", 3).isEmpty());
        assertTrue(Brackets.near("abc", -1).isEmpty(), "a caret before the text");
        assertTrue(Brackets.near("abc", 99).isEmpty(), "a caret past the text");
        assertEquals(OptionalInt.of(2), Brackets.near("ab)", 3), "caret at the very end, bracket just before it");
        assertEquals(OptionalInt.of(0), Brackets.near("(ab", 0));
    }

    @Test void anOpeningBracketPairsForwardAndAClosingOneBackward() {
        assertEquals(new Brackets.Pair(1, 11), Brackets.pair(TEXT, 1).orElseThrow());
        assertEquals(new Brackets.Pair(11, 1), Brackets.pair(TEXT, 11).orElseThrow());
        assertEquals(new Brackets.Pair(3, 5), Brackets.pair(TEXT, 3).orElseThrow());
        assertEquals(new Brackets.Pair(5, 3), Brackets.pair(TEXT, 5).orElseThrow());
        assertEquals(new Brackets.Pair(8, 10), Brackets.pair(TEXT, 8).orElseThrow());
        assertEquals(new Brackets.Pair(10, 8), Brackets.pair(TEXT, 10).orElseThrow());
    }

    @Test void nestedBracketsOfTheSameKindAreCounted() {
        String text = "((a)(b))";
        assertEquals(7, Brackets.pair(text, 0).orElseThrow().partner());
        assertEquals(3, Brackets.pair(text, 1).orElseThrow().partner());
        assertEquals(1, Brackets.pair(text, 3).orElseThrow().partner());
        assertEquals(4, Brackets.pair(text, 6).orElseThrow().partner());
        assertEquals(0, Brackets.pair(text, 7).orElseThrow().partner());
    }

    @Test void otherKindsOfBracketInBetweenAreIgnored() {
        assertEquals(6, Brackets.pair("(a]b[c)", 0).orElseThrow().partner(), "only parentheses count when matching (");
        assertEquals(4, Brackets.pair("{ ( } )", 0).orElseThrow().partner(), "{ pairs with } even though a ( is still open");
    }

    @Test void anUnmatchedBracketHasNoPartner() {
        assertTrue(Brackets.pair("(a", 0).isEmpty());
        assertTrue(Brackets.pair("a)", 1).isEmpty());
        assertTrue(Brackets.pair("((a)", 0).isEmpty(), "the outer one is never closed");
    }

    @Test void nonBracketsAndBadOffsetsHaveNoPartner() {
        assertTrue(Brackets.pair(TEXT, 0).isEmpty(), "f is not a bracket");
        assertTrue(Brackets.pair(TEXT, -1).isEmpty());
        assertTrue(Brackets.pair(TEXT, TEXT.length()).isEmpty());
        assertTrue(Brackets.pair("", 0).isEmpty());
    }

    @Test void theSelectionRangeCoversBothBracketsWhicheverWayRound() {
        assertEquals(1, new Brackets.Pair(1, 11).start());
        assertEquals(12, new Brackets.Pair(1, 11).end());
        assertEquals(1, new Brackets.Pair(11, 1).start());
        assertEquals(12, new Brackets.Pair(11, 1).end());
    }

    @Test void bracketsInsideStringsAndCommentsCountLikeAnyOther() {
        // Documented limit (BUGS.md): the scan is textual, so the ")" inside the quotes is the one that closes the "(".
        assertEquals(4, Brackets.pair("f(\"a)\")", 1).orElseThrow().partner());
    }
}
