package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a model's reply to "change this code" into the text the code should become. A reply may be
 * JSON edits ({@link JsonEdits}), hold edit blocks (SEARCH / REPLACE), a unified diff in a code
 * block, or the whole replacement in one code block; the four are recognised in that order,
 * whatever the prompt asked for, because small models do not always do what they are told. Nothing
 * here touches a file or a window.
 */
public final class ChangeReply {
  private ChangeReply() {}

  public enum Format {
    JSON_EDITS,
    EDIT_BLOCKS,
    DIFF,
    WHOLE_BLOCK
  }

  /**
   * {@code code} is the new text of the part that was sent, {@code notes} what was repaired on the
   * way, {@code anchors} how each edit of an edit reply was found (empty for a diff or a whole
   * block). Every edit that was found by anything but its exact text also has a note.
   */
  public record Interpreted(
      String code,
      String explanation,
      int extraBlocks,
      Format format,
      List<Finding> notes,
      List<EditBlocks.Anchor> anchors) {
    public Interpreted {
      notes = List.copyOf(notes);
      anchors = List.copyOf(anchors);
    }

    public Interpreted(
        String code, String explanation, int extraBlocks, Format format, List<Finding> notes) {
      this(code, explanation, extraBlocks, format, notes, List.of());
    }
  }

  private static final java.util.regex.Pattern REASONING =
      java.util.regex.Pattern.compile("(?is)<think(?:ing)?>.*?(?:</think(?:ing)?>|\\z)");

  /**
   * The reply without the model's reasoning. Models that think aloud may put it in the answer
   * between {@code <think>} tags, and the code they try out while thinking is not the answer: the
   * first code block must come from after it. A reasoning section that never closes swallows the
   * rest, so such a reply proposes nothing.
   */
  public static String withoutReasoning(String reply) {
    return REASONING.matcher(reply).replaceAll("").strip();
  }

  public static Interpreted interpret(String rawReply, String original) throws AiException {
    return interpret(rawReply, original, null);
  }

  /**
   * As {@link #interpret(String, String)}; {@code fileName}, when not null, is the file the code
   * came from, and JSON edits that name another file are refused (nothing is applied to a file the
   * model was not talking about).
   */
  public static Interpreted interpret(String rawReply, String original, String fileName)
      throws AiException {
    return interpret(rawReply, original, fileName, null);
  }

  /**
   * As above; {@code languageId} (null when unknown) and the file's name tell whether the code is
   * Markdown, which is read differently: a reply that wraps the file in a {@code ```markdown} fence
   * keeps the fences inside the file, and a line of {@code =} that underlines a heading is not
   * taken for the divider of an edit block.
   */
  public static Interpreted interpret(
      String rawReply, String original, String fileName, String languageId) throws AiException {
    boolean markdown = MarkdownText.is(languageId, fileName);
    String reply = withoutReasoning(rawReply);
    if (JsonEdits.looksLike(reply)) {
      JsonEdits.Parsed parsed = JsonEdits.parse(reply);
      int number = 0;
      for (JsonEdits.FileEdit e : parsed.edits()) {
        number++;
        if (fileName != null && !JsonEdits.sameFile(e.file(), fileName))
          throw new AiException(
              AiException.Kind.BAD_RESPONSE,
              FailureReason.WRONG_FILE,
              "Edit "
                  + number
                  + " is for "
                  + e.file()
                  + ", but the code that was sent is from "
                  + fileName
                  + ". Nothing was applied.");
      }
      EditBlocks.Applied applied =
          EditBlocks.applyWithAnchors(
              original, parsed.edits().stream().map(JsonEdits.FileEdit::edit).toList());
      return new Interpreted(
          applied.text(),
          parsed.explanation(),
          0,
          Format.JSON_EDITS,
          looseAnchorNotes(applied.anchors()),
          applied.anchors());
    }
    if (EditBlocks.looksLike(reply)) {
      EditBlocks.Parsed parsed = EditBlocks.parse(reply, original);
      EditBlocks.Applied applied = EditBlocks.applyWithAnchors(original, parsed.edits());
      return new Interpreted(
          applied.text(),
          parsed.explanation(),
          0,
          Format.EDIT_BLOCKS,
          looseAnchorNotes(applied.anchors()),
          applied.anchors());
    }
    ResponseParser.Parsed block = ResponseParser.parse(reply, markdown);
    if (UnifiedPatch.looksLike(block.code()))
      return new Interpreted(
          UnifiedPatch.apply(original, block.code()),
          block.explanation(),
          block.extraBlocks(),
          Format.DIFF,
          List.of());
    List<Finding> notes = new ArrayList<>();
    String code = restoreIndentation(original, block.code(), notes);
    return new Interpreted(
        code, block.explanation(), block.extraBlocks(), Format.WHOLE_BLOCK, notes);
  }

  /** True when the text is a reply of JSON edits (see {@link JsonEdits#looksLike}). */
  public static boolean isJsonEdits(String reply) {
    return JsonEdits.looksLike(withoutReasoning(reply));
  }

  /** A warning for each edit that was found by anything but its exact text. */
  static List<Finding> looseAnchorNotes(List<EditBlocks.Anchor> anchors) {
    List<Finding> notes = new ArrayList<>();
    for (EditBlocks.Anchor a : anchors) {
      if (!a.rung().isLoose()) continue;
      if (a.rung() == AnchorRung.ALREADY_APPLIED) {
        notes.add(
            Finding.warning(
                "Edit "
                    + a.edit()
                    + " was skipped: its change is already made by an earlier edit of the reply."));
        continue;
      }
      notes.add(
          Finding.warning(
              a.rung() == AnchorRung.SIGNATURE
                  ? "Edit "
                      + a.edit()
                      + " did not match the code as written, so it was placed by the signature of"
                      + " its method and the whole method was replaced. Read the change closely."
                  : "Edit "
                      + a.edit()
                      + " was found "
                      + a.rung().label()
                      + ": the model's copy of the code was not exact."));
    }
    return notes;
  }

  /**
   * Models very often hand code back without the indentation it had in the file, so it would land
   * flush left in the middle of a method. When the reply's first line has no indentation and the
   * first line that was sent had some, the sent indentation is put in front of every line (the
   * lines keep their indentation relative to each other). A reply that starts with some indentation
   * of its own is left alone, whatever it is.
   */
  static String restoreIndentation(String original, String code, List<Finding> notes) {
    List<String> sent = LineMatch.split(original);
    List<String> reply = LineMatch.split(code);
    String want = LineMatch.firstIndent(sent);
    if (want.isEmpty() || !LineMatch.firstIndent(reply).isEmpty()) return code;
    List<String> fixed = LineMatch.shiftIndent(reply, want, "");
    notes.add(
        Finding.warning(
            "The reply started with no indentation, so the indentation of the selected code was"
                + " added to every line."));
    return String.join("\n", fixed);
  }
}
