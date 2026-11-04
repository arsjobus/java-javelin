package com.javelin.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.editor.Indentation.Edit;
import org.junit.jupiter.api.Test;

/**
 * Enter with auto-indent: one line break per key press (the reported bug was two), with the right
 * indentation.
 */
class NewlineTest {
  private static final String UNIT = "    ";

  private static long lineBreaks(String text) {
    return text.chars().filter(c -> c == '\n').count();
  }

  /** Applies the edit and shows the caret as {@code |}. */
  private static String show(String text, int from, int to) {
    Edit edit = Indentation.newline(text, from, to, UNIT);
    String result = edit.apply(text);
    assertEquals(
        edit.selectionStart(), edit.selectionEnd(), "Enter leaves a caret, not a selection");
    return result.substring(0, edit.selectionStart())
        + "|"
        + result.substring(edit.selectionStart());
  }

  private static String show(String text, int caret) {
    return show(text, caret, caret);
  }

  // ---- the reported bug: exactly one new line ----

  @Test
  void enterAtTheEndOfALineAddsExactlyOneLineBreak() {
    String text = "int x = 1;";
    Edit edit = Indentation.newline(text, text.length(), text.length(), UNIT);
    assertEquals("\n", edit.replacement());
    assertEquals(1, lineBreaks(edit.apply(text)));
    assertEquals("int x = 1;\n|", show(text, text.length()));
  }

  @Test
  void enterNeverAddsMoreThanOneLineBreakWhateverTheContext() {
    String[] texts = {"", "a", "a\n", "\n", "  a", "\ta {", "x(\n  y", "{\n}", "\n\n\n", "  \n  "};
    for (String text : texts)
      for (int caret = 0; caret <= text.length(); caret++)
        assertEquals(
            lineBreaks(text) + 1,
            lineBreaks(Indentation.newline(text, caret, caret, UNIT).apply(text)),
            "text=" + text.replace("\n", "\\n") + " caret=" + caret);
  }

  @Test
  void enterInAnEmptyDocumentAddsOneLineBreak() {
    assertEquals("\n|", show("", 0));
  }

  @Test
  void enterOnABlankLineBetweenParagraphsAddsOneBlankLine() {
    assertEquals(
        "a\n\n|\nb", show("a\n\nb", 2)); // caret on the empty line: one more empty line, not two
  }

  // ---- keeping the indentation ----

  @Test
  void enterKeepsTheIndentationOfTheCurrentLine() {
    assertEquals("    foo();\n    |", show("    foo();", 10));
    assertEquals("\tfoo();\n\t|", show("\tfoo();", 7));
    assertEquals("  \t foo\n  \t |", show("  \t foo", 7));
  }

  @Test
  void enterOnAWhitespaceOnlyLineKeepsItsWhitespace() {
    assertEquals("   \n   |", show("   ", 3));
  }

  @Test
  void enterAddsOneLevelAfterAnOpeningBracket() {
    assertEquals("void f() {\n    |", show("void f() {", 10));
    assertEquals("a[\n    |", show("a[", 2));
    assertEquals("call(\n    |", show("call(", 5));
    assertEquals("  if (x) {\n      |", show("  if (x) {", 10));
    assertEquals("\tif (x) {\n\t\t|", showUnit("\tif (x) {", 9, "\t"));
  }

  private static String showUnit(String text, int caret, String unit) {
    Edit edit = Indentation.newline(text, caret, caret, unit);
    String result = edit.apply(text);
    return result.substring(0, edit.selectionStart())
        + "|"
        + result.substring(edit.selectionStart());
  }

  @Test
  void trailingSpacesAfterAnOpeningBracketStillIndent() {
    assertEquals("f(  \n    |", show("f(  ", 4));
  }

  @Test
  void aClosingBracketOrPlainTextAddsNoExtraLevel() {
    assertEquals("f()\n|", show("f()", 3));
    assertEquals("}\n|", show("}", 1));
    assertEquals("  x;\n  |", show("  x;", 4));
  }

  @Test
  void onlyTextBeforeTheCaretDecidesTheIndentation() {
    assertEquals("\n|    foo {", show("    foo {", 0)); // caret at the very start: nothing to keep
    assertEquals("  \n  |ab", show("  ab", 2)); // inside the indentation: keep what is to its left
    assertEquals("  a\n  |b {", show("  ab {", 3)); // the '{' is after the caret, so no extra level
    assertEquals("f(\n    |x)", show("f(x)", 2)); // a bracket just before the caret does
  }

  @Test
  void enterOnALaterLineUsesThatLineNotTheFirst() {
    assertEquals("a\n  b\n  |", show("a\n  b", 5));
  }

  // ---- a selection is replaced ----

  @Test
  void enterReplacesTheSelection() {
    assertEquals("ab\n|ef", show("abcdef", 2, 4));
    Edit edit = Indentation.newline("  abcdef", 4, 6, UNIT);
    assertEquals("  ab\n  ef", edit.apply("  abcdef"));
    assertEquals(7, edit.selectionStart());
    assertEquals(1, lineBreaks(edit.apply("  abcdef")));
  }

  @Test
  void enterReplacesAMultiLineSelectionWithOneLineBreak() {
    assertEquals("a\n|d", show("a1\n2\n3d", 1, 6));
  }

  // ---- the Enter key's second event ----

  @Test
  void theTypedEventForEnterIsRecognisedSoItCanBeSwallowed() {
    assertTrue(Indentation.isLineBreak("\n"));
    assertTrue(Indentation.isLineBreak("\r"));
    assertTrue(Indentation.isLineBreak("\r\n"));
  }

  @Test
  void otherTypedCharactersAreNotSwallowed() {
    assertFalse(Indentation.isLineBreak(""));
    assertFalse(Indentation.isLineBreak("\t"));
    assertFalse(Indentation.isLineBreak("a"));
    assertFalse(Indentation.isLineBreak(" "));
    assertFalse(Indentation.isLineBreak(null));
  }
}
