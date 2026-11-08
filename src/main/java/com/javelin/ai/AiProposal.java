package com.javelin.ai;

import java.util.List;

/**
 * A change the AI suggested, held until the user applies or rejects it. It records the text it was
 * made for, so that it is never applied to text that has since changed. Applying is the user's
 * action; nothing here touches a file.
 */
public record AiProposal(
    Kind kind,
    String documentName,
    int start,
    int end,
    String original,
    String replacement,
    String suggestedName,
    String explanation,
    List<EditBlocks.Anchor> anchors) {
  public AiProposal {
    anchors = List.copyOf(anchors);
  }

  public enum Kind {
    /** Replace {@code [start, end)} of the document the request came from. */
    REPLACE_RANGE,
    /**
     * A new document (generated tests); it is opened unsaved and the user chooses where to save it.
     */
    NEW_DOCUMENT
  }

  /** A replacement for the text that was sent. */
  public static AiProposal replace(AiContext context, String code, String explanation) {
    return replace(context, code, explanation, List.of());
  }

  /**
   * A replacement for the text that was sent; {@code anchors} says how each of the model's edits
   * was found in it.
   */
  public static AiProposal replace(
      AiContext context, String code, String explanation, List<EditBlocks.Anchor> anchors) {
    return new AiProposal(
        Kind.REPLACE_RANGE,
        context.name(),
        context.start(),
        context.end(),
        context.text(),
        ResponseParser.matchLineEndings(
            context.text(), ResponseParser.matchTrailingNewline(context.text(), code)),
        null,
        explanation,
        anchors);
  }

  /** A new document with the suggested file name. */
  public static AiProposal newDocument(String suggestedName, String code, String explanation) {
    return new AiProposal(
        Kind.NEW_DOCUMENT,
        suggestedName,
        0,
        0,
        "",
        code.endsWith("\n") ? code : code + "\n",
        suggestedName,
        explanation,
        List.of());
  }

  /** False when the document no longer holds the text the proposal was made for. */
  public boolean isStillValid(String current) {
    return kind == Kind.NEW_DOCUMENT
        || (end <= current.length()
            && current.regionMatches(start, original, 0, original.length()));
  }

  /**
   * The document's text with the proposal applied (for a new document, just its text). Call {@link
   * #isStillValid} first.
   */
  public String applyTo(String current) {
    if (kind == Kind.NEW_DOCUMENT) return replacement;
    if (!isStillValid(current))
      throw new IllegalStateException("The text changed since the proposal was made");
    return current.substring(0, start) + replacement + current.substring(end);
  }

  /** The change as a unified diff with three lines of context. */
  public LineDiff.Result diff() {
    return LineDiff.unified(original, replacement, 3);
  }

  /** One line for dialogs and the status bar. */
  public String summary() {
    LineDiff.Result diff = diff();
    return kind == Kind.NEW_DOCUMENT
        ? "New file " + suggestedName + ": " + diff.added() + " line(s)"
        : "Change to " + documentName + ": +" + diff.added() + " -" + diff.removed();
  }
}
