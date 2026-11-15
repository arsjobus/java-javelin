package com.javelin.ai;

/**
 * Asking a model once more when its edit blocks could not be used, with the reason. A small model
 * that has copied a long run of code often stops before it has written the change, or runs on into
 * the fence of the text it was sent; told what was wrong with its own answer it usually gets the
 * block right the second time. Nothing here sends anything: it only decides whether a second try
 * is worth it and writes the request for it.
 */
public final class EditRepair {
  private EditRepair() {}

  /** The most of the earlier answer that is quoted back to the model, in characters. */
  static final int MAX_QUOTED_CHARS = 6000;

  /**
   * True when the reply was meant as edit blocks (it has a SEARCH or RANGE marker) and failed in a
   * way the model can mend: a block that is not complete, or lines that are in no place or in
   * several. A reply of plain words, a file the model may not change or a reply the provider cut
   * off are not asked again here.
   */
  public static boolean worthAsking(FailureReason reason, String reply) {
    if (reason != FailureReason.UNPARSEABLE
        && reason != FailureReason.ANCHOR_NOT_FOUND
        && reason != FailureReason.TRUNCATED) return false;
    return reply != null && EditBlocks.looksLike(ChangeReply.withoutReasoning(reply));
  }

  /**
   * The request again, with the answer that failed and what was wrong with it after the original
   * prompt. A window that was chosen by the caller grows by what was added.
   */
  public static AiRequest again(AiRequest request, String reply, String problem) {
    String added =
        "\n\nYour previous answer to this request could not be used: "
            + cleaned(problem)
            + "\n\n----- YOUR PREVIOUS ANSWER -----\n"
            + clip(ChangeReply.withoutReasoning(reply))
            + "\n----- END OF YOUR PREVIOUS ANSWER -----\n\n"
            + "Answer the request again from the start. Write every edit block complete: a line"
            + " <<<<<<< SEARCH, the lines copied from the code, a line =======, the new lines and a"
            + " line >>>>>>> REPLACE. To add code, SEARCH for the line it goes after and REPLACE it"
            + " with that line followed by the new code. Never copy a line that starts with"
            + " ===== BEGIN or ===== END into an edit block, and write each change once.";
    int window = request.contextTokens();
    if (window > 0) window += added.length() / 3 + 1024;
    return new AiRequest(
        request.model(),
        request.system(),
        request.prompt() + added,
        window,
        request.sampling(),
        request.responseSchema());
  }

  /** The problem as one short paragraph, without the pointer to the panel meant for the person. */
  static String cleaned(String problem) {
    String text = problem == null ? "" : problem.strip();
    text = text.replaceAll("\\s*Its text is in the AI panel\\.?", "");
    return text.replaceAll("\\s*\\R\\s*", " ").strip();
  }

  static String clip(String text) {
    if (text.length() <= MAX_QUOTED_CHARS) return text;
    int half = MAX_QUOTED_CHARS / 2;
    return text.substring(0, half) + "\n[...]\n" + text.substring(text.length() - half);
  }
}
