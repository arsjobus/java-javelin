package com.javelin.ai;

/**
 * The part of the document that is sent: the selection, or for actions that allow it the whole
 * file, cut to the context budget. {@code start}/{@code end} say where in the document {@code text}
 * came from, so a proposal can be applied back.
 */
public record AiContext(
    String name,
    String languageId,
    String text,
    Scope scope,
    int start,
    int end,
    boolean truncated,
    int totalChars) {
  public enum Scope {
    SELECTION("selection"),
    WHOLE_FILE("whole file");

    private final String label;

    Scope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * Chooses what to send. The selection is used when there is one; otherwise the whole file if
   * {@code action} accepts it. For Propose Change the selection is widened to whole lines: a
   * selection that starts in the middle of a line would hand the model code without its indentation
   * and take back a replacement that cannot be spliced in cleanly. Text over {@code maxChars} is
   * cut there and marked as cut, except when the action replaces what it is given: a change
   * proposed for text the model only saw part of could delete the rest, so that is refused.
   */
  public static AiContext of(AiAction action, AiSource source, int maxChars) throws AiException {
    boolean selection = source.hasSelection();
    if (!selection && action.needsSelection())
      throw new AiException(
          AiException.Kind.REFUSED,
          "Select the code first: " + action.label() + " works on the selected text.");
    int start = selection ? source.selectionStart() : 0;
    int end = selection ? source.selectionEnd() : source.text().length();
    if (selection
        && action
            == AiAction
                .PROPOSE_CHANGE) { // a change replaces whole lines, so the model sees them with
      // their indentation and the splice is clean
      start = lineStart(source.text(), start);
      end = lineEnd(source.text(), end);
    }
    if (end == start)
      throw new AiException(
          AiException.Kind.REFUSED, "There is no text to send: the document is empty.");
    int total = end - start;
    boolean truncated = total > maxChars;
    if (truncated && action == AiAction.PROPOSE_CHANGE)
      throw new AiException(
          AiException.Kind.REFUSED,
          "The selection is "
              + total
              + " characters, over the limit of "
              + maxChars
              + ". Select less code, or raise ai.maxContextChars in your user settings.");
    if (truncated) {
      end = start + maxChars;
      if (Character.isHighSurrogate(source.text().charAt(end - 1)))
        end--; // never cut a character in two
    }
    return new AiContext(
        source.name(),
        source.languageId(),
        source.text().substring(start, end),
        selection ? Scope.SELECTION : Scope.WHOLE_FILE,
        start,
        end,
        truncated,
        total);
  }

  private static int lineStart(String text, int offset) {
    int i = offset;
    while (i > 0 && text.charAt(i - 1) != '\n') i--;
    return i;
  }

  /**
   * The end of the line holding {@code offset}, after its line break; an offset already at the
   * start of a line stays.
   */
  private static int lineEnd(String text, int offset) {
    if (offset == 0 || text.charAt(offset - 1) == '\n') return offset;
    int i = text.indexOf('\n', offset);
    return i < 0 ? text.length() : i + 1;
  }
}
