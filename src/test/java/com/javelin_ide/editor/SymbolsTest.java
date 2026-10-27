package com.javelin_ide.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SymbolsTest {
    private static final String TEXT = "int total_1 = foo(bar);";

    @Test void wordAtReturnsTheWholeIdentifierFromAnyOfItsCharacters() {
        for (int offset = 4; offset <= 10; offset++) assertEquals("total_1", Symbols.wordAt(TEXT, offset), "offset " + offset);
        assertEquals("foo", Symbols.wordAt(TEXT, 14));
    }

    @Test void wordAtIsEmptyOffIdentifiersAndOutOfRange() {
        assertEquals("", Symbols.wordAt(TEXT, 3), "space");
        assertEquals("", Symbols.wordAt(TEXT, 11), "space");
        assertEquals("", Symbols.wordAt(TEXT, 22), "semicolon");
        assertEquals("", Symbols.wordAt(TEXT, -1));
        assertEquals("", Symbols.wordAt(TEXT, TEXT.length()));
        assertEquals("", Symbols.wordAt("", 0));
    }

    @Test void caretInsideOrBeforeAWordStaysPut() {
        assertEquals(6, Symbols.symbolOffset(TEXT, 6));
        assertEquals(4, Symbols.symbolOffset(TEXT, 4));
    }

    @Test void caretJustAfterAWordMovesOntoIt() {
        assertEquals(10, Symbols.symbolOffset(TEXT, 11), "after total_1");
        assertEquals(16, Symbols.symbolOffset(TEXT, 17), "after foo, before the bracket");
    }

    @Test void caretOnPunctuationOrAtTheEdgesIsLeftAlone() {
        assertEquals(0, Symbols.symbolOffset(TEXT, 0));
        assertEquals(TEXT.length(), Symbols.symbolOffset(TEXT, TEXT.length()), "after the semicolon");
        assertEquals(0, Symbols.symbolOffset("", 0));
        assertEquals(6, Symbols.symbolOffset("a  =  b", 7), "after the last word of the text");
    }
}
