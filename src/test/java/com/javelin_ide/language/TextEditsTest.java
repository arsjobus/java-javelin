package com.javelin_ide.language;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextEditsTest {
    private static Lsp.Position p(int line, int ch) { return new Lsp.Position(line, ch); }
    private static Lsp.TextEdit edit(int l1, int c1, int l2, int c2, String text) { return new Lsp.TextEdit(new Lsp.Range(p(l1, c1), p(l2, c2)), text); }

    @Test void offsetsCountLinesAndColumns() {
        String text = "ab\ncde\nf";
        assertEquals(0, TextEdits.offset(text, p(0, 0)));
        assertEquals(1, TextEdits.offset(text, p(0, 1)));
        assertEquals(3, TextEdits.offset(text, p(1, 0)));
        assertEquals(5, TextEdits.offset(text, p(1, 2)));
        assertEquals(7, TextEdits.offset(text, p(2, 0)));
    }

    @Test void positionsPastTheEndAreClamped() {
        String text = "ab\ncde\nf";
        assertEquals(2, TextEdits.offset(text, p(0, 99)), "a column past the line end stops at the line end");
        assertEquals(8, TextEdits.offset(text, p(9, 9)), "a line past the end means the end of the last line");
        assertEquals(0, TextEdits.offset(text, p(-1, -4)), "negative positions mean the start");
        assertEquals(0, TextEdits.offset("", p(3, 3)));
    }

    @Test void editsApplyAgainstTheOriginalTextFromTheBottomUp() {
        String text = "foo bar\nfoo baz\n";
        String out = TextEdits.apply(text, List.of(edit(0, 0, 0, 3, "qux"), edit(1, 0, 1, 3, "quux")));
        assertEquals("qux bar\nquux baz\n", out);
        assertEquals("qux bar\nquux baz\n", TextEdits.apply(text, List.of(edit(1, 0, 1, 3, "quux"), edit(0, 0, 0, 3, "qux"))), "list order does not matter for separate places");
    }

    @Test void insertsAtTheSamePositionKeepTheOrderOfTheList() {
        assertEquals("abXYc", TextEdits.apply("abc", List.of(edit(0, 2, 0, 2, "X"), edit(0, 2, 0, 2, "Y"))));
    }

    @Test void resolvedChangesComeBottomUpWithAbsoluteOffsets() {
        List<TextEdits.Change> changes = TextEdits.resolve("ab\ncd\n", List.of(edit(0, 0, 0, 1, "X"), edit(1, 1, 1, 2, "Y")));
        assertEquals(List.of(new TextEdits.Change(4, 5, "Y"), new TextEdits.Change(0, 1, "X")), changes);
    }

    @Test void noEditsLeaveTheTextAlone() {
        assertEquals("same", TextEdits.apply("same", List.of()));
    }

    @Test void anEndBeforeItsStartIsTreatedAsAnInsert() {
        assertEquals("abXc", TextEdits.apply("abc", List.of(edit(0, 2, 0, 1, "X"))));
    }

    @Test void aMinimalChangeKeepsSharedLeadingAndTrailingText() {
        TextEdits.Change c = TextEdits.minimal("class Foo {}", "class Bar {}");
        assertEquals(new TextEdits.Change(6, 9, "Bar"), c);
    }

    @Test void aMinimalChangeCanBeAPureInsertOrDelete() {
        assertEquals(new TextEdits.Change(2, 2, "XY"), TextEdits.minimal("abcd", "abXYcd"));
        assertEquals(new TextEdits.Change(2, 4, ""), TextEdits.minimal("abXYcd", "abcd"));
        assertEquals(new TextEdits.Change(0, 0, "x"), TextEdits.minimal("", "x"));
        assertEquals(new TextEdits.Change(0, 1, ""), TextEdits.minimal("x", ""));
    }

    @Test void identicalTextNeedsNoChange() {
        assertNull(TextEdits.minimal("same", "same"));
        assertNull(TextEdits.minimal("", ""));
    }

    @Test void repeatedCharactersAreNotCountedTwice() {
        // "aa" -> "aaa": the prefix takes both old characters, so the suffix must not reuse them.
        assertEquals(new TextEdits.Change(2, 2, "a"), TextEdits.minimal("aa", "aaa"));
    }
}
