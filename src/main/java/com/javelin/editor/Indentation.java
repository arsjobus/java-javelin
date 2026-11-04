package com.javelin.editor;

/**
 * Tab / Shift+Tab / Enter behaviour as pure text edits, so it can be tested without JavaFX.
 *
 * <p>All positions are offsets into the document text. An {@link Edit} replaces {@code [start,
 * end)} with {@code replacement} in one operation (one undo step) and says where the selection
 * should be afterwards, in the coordinates of the <em>new</em> text.
 */
public final class Indentation {
  private Indentation() {}

  /** One replacement plus the selection to restore after it. */
  public record Edit(int start, int end, String replacement, int selectionStart, int selectionEnd) {
    public String apply(String text) {
      return text.substring(0, start) + replacement + text.substring(end);
    }

    /** True when applying this edit would not change the text. */
    public boolean isNoOp() {
      return start == end && replacement.isEmpty();
    }
  }

  /**
   * What the Tab key does. A selection that spans several lines indents all of them; otherwise the
   * selection (if any) is replaced by a tab character, or by spaces up to the next tab stop when
   * {@code insertSpaces}.
   */
  public static Edit tab(String text, int selStart, int selEnd, int tabSize, boolean insertSpaces) {
    if (text.substring(selStart, selEnd).indexOf('\n') >= 0)
      return indent(text, selStart, selEnd, tabSize, insertSpaces);
    String insert =
        insertSpaces ? " ".repeat(tabSize - column(text, selStart, tabSize) % tabSize) : "\t";
    int caret = selStart + insert.length();
    return new Edit(selStart, selEnd, insert, caret, caret);
  }

  /** Indents every line the selection touches by one level (blank lines are left alone). */
  public static Edit indent(
      String text, int selStart, int selEnd, int tabSize, boolean insertSpaces) {
    String unit = insertSpaces ? " ".repeat(tabSize) : "\t";
    int first = lineStart(text, selStart),
        last = lastLineStart(text, selStart, selEnd),
        regionEnd = lineEnd(text, last);
    StringBuilder out = new StringBuilder();
    int added = 0, addedBeforeStart = 0;
    for (int line = first; ; ) {
      int end = lineEnd(text, line);
      if (end > line) {
        out.append(unit);
        added += unit.length();
        if (line == first && selStart > first) addedBeforeStart = unit.length();
      }
      out.append(text, line, end);
      if (end >= regionEnd) break;
      out.append('\n');
      line = end + 1;
    }
    return new Edit(first, regionEnd, out.toString(), selStart + addedBeforeStart, selEnd + added);
  }

  /**
   * What Shift+Tab does: removes one level of indentation (a tab, or up to {@code tabSize} spaces)
   * from every line the selection touches, or from the caret's line when nothing is selected.
   */
  public static Edit outdent(String text, int selStart, int selEnd, int tabSize) {
    int first = lineStart(text, selStart),
        last = lastLineStart(text, selStart, selEnd),
        regionEnd = lineEnd(text, last);
    StringBuilder out = new StringBuilder();
    int removedFirst = 0, removedLast = 0, removedMiddle = 0;
    for (int line = first; ; ) {
      int end = lineEnd(text, line), remove = 0;
      if (line < end && text.charAt(line) == '\t') remove = 1;
      else
        while (remove < tabSize && line + remove < end && text.charAt(line + remove) == ' ')
          remove++;
      out.append(text, line + remove, end);
      if (line == first) removedFirst = remove;
      else if (line == last) removedLast = remove;
      else removedMiddle += remove;
      if (end >= regionEnd) break;
      out.append('\n');
      line = end + 1;
    }
    if (removedFirst + removedMiddle + removedLast == 0)
      return new Edit(selStart, selStart, "", selStart, selEnd);
    int newStart = selStart - Math.min(removedFirst, selStart - first);
    int newEnd =
        first == last
            ? selEnd - Math.min(removedFirst, selEnd - first)
            : selEnd - removedFirst - removedMiddle - Math.min(removedLast, selEnd - last);
    return new Edit(first, regionEnd, out.toString(), newStart, newEnd);
  }

  /**
   * What Enter does with auto-indent on: replaces the selection (if any) with exactly one line
   * break followed by the indentation of the line the caret is on, plus one extra level when the
   * text before the caret ends in an opening bracket. Only the text <em>before</em> the caret
   * counts, so pressing Enter at the start of an indented line, or in the middle of one, does not
   * invent indentation from text that ends up on the line below.
   */
  public static Edit newline(String text, int selStart, int selEnd, String indentUnit) {
    String before = text.substring(lineStart(text, selStart), selStart);
    int whitespace = 0;
    while (whitespace < before.length()
        && (before.charAt(whitespace) == ' ' || before.charAt(whitespace) == '\t')) whitespace++;
    String indent = before.substring(0, whitespace);
    String code = before.stripTrailing();
    if (code.endsWith("{") || code.endsWith("[") || code.endsWith("(")) indent += indentUnit;
    String insert = "\n" + indent;
    int caret = selStart + insert.length();
    return new Edit(selStart, selEnd, insert, caret, caret);
  }

  /**
   * True for the character a KEY_TYPED event carries for the Enter key. Enter arrives twice
   * (pressed, then typed), and consuming the pressed event does not stop the typed one, so after
   * handling Enter itself the editor must swallow this one or the line break is inserted a second
   * time.
   */
  public static boolean isLineBreak(String typed) {
    return "\n".equals(typed) || "\r".equals(typed) || "\r\n".equals(typed);
  }

  /** On-screen column of {@code pos}, counting a tab as "up to the next tab stop". */
  static int column(String text, int pos, int tabSize) {
    int column = 0;
    for (int i = lineStart(text, pos); i < pos; i++)
      column = text.charAt(i) == '\t' ? column + tabSize - column % tabSize : column + 1;
    return column;
  }

  private static int lineStart(String text, int pos) {
    return text.lastIndexOf('\n', pos - 1) + 1;
  }

  private static int lineEnd(String text, int pos) {
    int newline = text.indexOf('\n', pos);
    return newline < 0 ? text.length() : newline;
  }

  /**
   * Start of the last line a selection touches; a selection ending exactly at a line start does not
   * touch that line.
   */
  private static int lastLineStart(String text, int selStart, int selEnd) {
    return lineStart(
        text, selEnd > selStart && text.charAt(selEnd - 1) == '\n' ? selEnd - 1 : selEnd);
  }
}
