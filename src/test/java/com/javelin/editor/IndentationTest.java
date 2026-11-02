package com.javelin.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.editor.Indentation.Edit;
import org.junit.jupiter.api.Test;

class IndentationTest {
    /** Applies an edit and returns the new text with the selection shown as {@code [ ]} (or {@code |} for a caret). */
    private static String show(String text, Edit edit) {
        String result = edit.apply(text);
        int a = edit.selectionStart(), b = edit.selectionEnd();
        return a == b ? result.substring(0, a) + "|" + result.substring(a)
                      : result.substring(0, a) + "[" + result.substring(a, b) + "]" + result.substring(b);
    }

    // ---- Tab with nothing (or part of one line) selected: the reported bug ----

    @Test void tabWithSpacesInsertsExactlyOneTabStopAndNoTabCharacter() {
        Edit edit = Indentation.tab("", 0, 0, 2, true);
        assertEquals("  ", edit.apply(""));
        assertFalse(edit.apply("").contains("\t"));
        assertEquals(2, edit.selectionStart());
        assertEquals(2, edit.selectionEnd());
    }

    @Test void tabWithSpacesPadsToTheNextTabStop() {
        assertEquals("a   |", show("a", Indentation.tab("a", 1, 1, 4, true)));
        assertEquals("ab  |", show("ab", Indentation.tab("ab", 2, 2, 4, true)));
        assertEquals("abcd    |", show("abcd", Indentation.tab("abcd", 4, 4, 4, true)));
        assertEquals("x\n  |", show("x\n", Indentation.tab("x\n", 2, 2, 2, true)));
    }

    @Test void tabWithSpacesInTheMiddleOfALineKeepsTheRest() {
        assertEquals("a  |bc", show("abc", Indentation.tab("abc", 1, 1, 3, true)));
    }

    @Test void tabWithSpacesCountsAnExistingTabCharacterAsAFullTabStop() {
        assertEquals("\t    |", show("\t", Indentation.tab("\t", 1, 1, 4, true)));   // tab ended at column 4: a full new stop
        assertEquals("\ta   |", show("\ta", Indentation.tab("\ta", 2, 2, 4, true)));   // column 5: pad 3 to reach 8
        assertEquals(0, Indentation.column("", 0, 4));
        assertEquals(4, Indentation.column("\t", 1, 4));
        assertEquals(5, Indentation.column("\ta", 2, 4));
        assertEquals(8, Indentation.column("a\t\t", 3, 4));
    }

    @Test void tabWithRealTabsInsertsOneTabCharacter() {
        assertEquals("\t|", show("", Indentation.tab("", 0, 0, 4, false)));
        assertEquals("a\t|b", show("ab", Indentation.tab("ab", 1, 1, 8, false)));
    }

    @Test void tabReplacesASelectionThatStaysOnOneLine() {
        assertEquals("a |d", show("abcd", Indentation.tab("abcd", 1, 3, 2, true)));   // column 1 -> next stop at 2
        assertEquals("a\t|d", show("abcd", Indentation.tab("abcd", 1, 3, 2, false)));
    }

    // ---- Tab / Shift+Tab on a multi-line selection: indent and outdent ----

    @Test void tabIndentsEveryLineOfAMultiLineSelection() {
        String text = "one\ntwo\nthree";
        assertEquals("[  one\n  two\n  three]", show(text, Indentation.tab(text, 0, text.length(), 2, true)));
        Edit edit = Indentation.tab(text, 0, text.length(), 2, true);
        assertEquals("  one\n  two\n  three", edit.apply(text));
    }

    @Test void tabIndentsWithRealTabsWhenInsertSpacesIsOff() {
        String text = "a\nb";
        assertEquals("\ta\n\tb", Indentation.tab(text, 0, 3, 4, false).apply(text));
    }

    @Test void selectionKeepsCoveringTheSameTextAfterIndenting() {
        String text = "foo\nbar\nbaz";
        Edit edit = Indentation.indent(text, 1, 9, 2, true);   // from the 'o' in foo to the 'a' in baz
        String result = edit.apply(text);
        assertEquals("  foo\n  bar\n  baz", result);
        assertEquals("oo\n  bar\n  b", result.substring(edit.selectionStart(), edit.selectionEnd()));
    }

    @Test void selectionStartingAtColumnZeroStillCoversTheFirstIndentedLine() {
        String text = "foo\nbar";
        Edit edit = Indentation.indent(text, 0, 7, 2, true);
        assertEquals(0, edit.selectionStart());
        assertEquals("  foo\n  bar", edit.apply(text).substring(edit.selectionStart(), edit.selectionEnd()));
    }

    @Test void aSelectionEndingAtTheStartOfALineDoesNotTouchThatLine() {
        String text = "foo\nbar\nbaz";
        Edit edit = Indentation.tab(text, 0, 8, 2, true);   // ends right after "bar\n"
        assertEquals("  foo\n  bar\nbaz", edit.apply(text));
        assertEquals("  foo\n  bar\n", edit.apply(text).substring(edit.selectionStart(), edit.selectionEnd()));
    }

    @Test void blankLinesAreNotIndented() {
        String text = "a\n\nb";
        assertEquals("  a\n\n  b", Indentation.indent(text, 0, 4, 2, true).apply(text));
    }

    @Test void indentingAnIndentedLineAddsAnotherLevel() {
        String text = "  a\nb";
        assertEquals("    a\n  b", Indentation.indent(text, 0, 5, 2, true).apply(text));
    }

    @Test void shiftTabOutdentsEveryLineOfAMultiLineSelection() {
        String text = "    one\n    two\n    three";
        Edit edit = Indentation.outdent(text, 0, text.length(), 2);
        assertEquals("  one\n  two\n  three", edit.apply(text));
        assertEquals("  one\n  two\n  three", edit.apply(text).substring(edit.selectionStart(), edit.selectionEnd()));
    }

    @Test void shiftTabRemovesATabCharacterOrUpToTabSizeSpaces() {
        assertEquals("a", Indentation.outdent("\ta", 1, 1, 4).apply("\ta"));
        assertEquals("a", Indentation.outdent("    a", 4, 4, 4).apply("    a"));
        assertEquals("  a", Indentation.outdent("      a", 6, 6, 4).apply("      a"));   // 6 spaces, size 4: removes 4
        assertEquals("a", Indentation.outdent("  a", 2, 2, 4).apply("  a"));              // fewer spaces than a full level
        assertEquals(" a", Indentation.outdent("\t a", 2, 2, 4).apply("\t a"));           // tab first: only the tab goes
    }

    @Test void shiftTabWithoutASelectionOutdentsTheCaretLineAndMovesTheCaretBack() {
        String text = "x\n    y\nz";
        assertEquals("x\n  y|\nz", show(text, Indentation.outdent(text, 7, 7, 2)));
        assertEquals("x\n|y\nz", show(text, Indentation.outdent(text, 2, 2, 4)));       // caret at column 0 stays put
        assertEquals("x\n|y\nz", show(text, Indentation.outdent(text, 4, 4, 4)));       // caret inside the indent goes to column 0
    }

    @Test void shiftTabClampsASelectionBoundaryThatSatInsideTheRemovedIndentation() {
        String text = "    a\n    b";
        Edit edit = Indentation.outdent(text, 1, 8, 4);   // starts inside the first indent, ends inside the second
        assertEquals("a\nb", edit.apply(text));
        assertEquals(0, edit.selectionStart());
        assertEquals(2, edit.selectionEnd());
    }

    @Test void shiftTabHandlesMiddleLinesAndASelectionEndingAtALineStart() {
        String text = "  a\n  b\n  c\nd";
        Edit edit = Indentation.outdent(text, 0, 12, 2);   // ends right after "c\n"
        assertEquals("a\nb\nc\nd", edit.apply(text));
        assertEquals("a\nb\nc\n", edit.apply(text).substring(edit.selectionStart(), edit.selectionEnd()));
    }

    @Test void shiftTabOnTextWithNothingToRemoveIsANoOp() {
        Edit edit = Indentation.outdent("a\nb", 0, 3, 4);
        assertTrue(edit.isNoOp());
        assertEquals("a\nb", edit.apply("a\nb"));
        assertEquals(0, edit.selectionStart());
        assertEquals(3, edit.selectionEnd());
        assertTrue(Indentation.outdent("", 0, 0, 2).isNoOp());
    }

    @Test void indentThenOutdentRoundTrips() {
        String text = "if (x) {\n  y();\n\n  z();\n}";
        Edit in = Indentation.indent(text, 0, text.length(), 4, true);
        String indented = in.apply(text);
        Edit out = Indentation.outdent(indented, in.selectionStart(), in.selectionEnd(), 4);
        assertEquals(text, out.apply(indented));
        Edit tabIn = Indentation.indent(text, 0, text.length(), 4, false);
        assertEquals(text, Indentation.outdent(tabIn.apply(text), tabIn.selectionStart(), tabIn.selectionEnd(), 4).apply(tabIn.apply(text)));
    }

    @Test void isNoOpIsFalseForARealEdit() {
        assertFalse(Indentation.tab("", 0, 0, 2, true).isNoOp());
        assertFalse(new Edit(0, 1, "", 0, 0).isNoOp());
    }
}
