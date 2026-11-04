package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Changes written as "find these lines, put these lines there" instead of as a whole new copy of
 * the code:
 *
 * <pre>
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH
 * the lines as they are now
 * =======
 * the lines as they should be
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * </pre>
 *
 * Small models drop, shorten or re-indent code when they have to write all of it again; copying a
 * few lines and changing some is much easier for them. The lines are found by content, never by
 * line number, so a model that cannot count does no harm. All or nothing: if one block cannot be
 * placed unambiguously, nothing is applied.
 */
public final class EditBlocks {
  private EditBlocks() {}

  public record Edit(String search, String replace) {}

  /** {@code explanation} is the text around the blocks. */
  public record Parsed(List<Edit> edits, String explanation) {}

  private static final Pattern SEARCH =
      Pattern.compile("^\\s*<{5,}\\s*SEARCH\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern DIVIDER = Pattern.compile("^\\s*={5,}\\s*$");
  private static final Pattern REPLACE =
      Pattern.compile("^\\s*>{5,}\\s*REPLACE\\s*$", Pattern.CASE_INSENSITIVE);

  /** True when the reply contains at least one SEARCH marker. */
  public static boolean looksLike(String reply) {
    for (String line : LineMatch.split(reply)) if (SEARCH.matcher(line).matches()) return true;
    return false;
  }

  public static Parsed parse(String reply) throws AiException {
    List<Edit> edits = new ArrayList<>();
    List<String> outside = new ArrayList<>();
    List<String> search = null, replace = null;
    for (String line : LineMatch.split(reply)) {
      if (search == null) { // outside a block
        if (SEARCH.matcher(line).matches()) search = new ArrayList<>();
        else if (!line.strip().startsWith("```") && !line.strip().startsWith("~~~"))
          outside.add(line);
      } else if (replace == null) { // in the SEARCH part
        if (DIVIDER.matcher(line).matches()) replace = new ArrayList<>();
        else search.add(line);
      } else { // in the REPLACE part
        if (REPLACE.matcher(line).matches()) {
          edits.add(new Edit(String.join("\n", search), String.join("\n", replace)));
          search = null;
          replace = null;
        } else {
          replace.add(line);
        }
      }
    }
    if (search != null)
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          "The reply stops inside an edit block (it was probably cut off), so nothing is proposed. Its text is in the AI panel.");
    if (edits.isEmpty())
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          "The reply has no complete edit block, so there is no change to propose. Its text is in the AI panel.");
    return new Parsed(edits, String.join("\n", outside).strip());
  }

  /**
   * The text with every edit made, one after the other (a later edit sees the result of an earlier
   * one).
   */
  public static String apply(String original, List<Edit> edits) throws AiException {
    List<String> lines = LineMatch.split(original);
    int number = 0;
    for (Edit edit : edits) {
      number++;
      List<String> search = LineMatch.split(edit.search());
      if (edit.search().isBlank())
        throw new AiException(
            AiException.Kind.BAD_RESPONSE,
            "Edit "
                + number
                + " has an empty SEARCH part, so it does not say which lines it changes. Nothing was applied.");
      List<LineMatch.Found> found = LineMatch.candidates(lines, search, 0);
      String first = search.stream().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
      if (found.isEmpty())
        throw new AiException(
            AiException.Kind.BAD_RESPONSE,
            "Edit "
                + number
                + " searches for lines that are not in the code (it starts with `"
                + shorten(first)
                + "`), so nothing was applied. The model probably changed or invented them.");
      if (found.size() > 1)
        throw new AiException(
            AiException.Kind.BAD_RESPONSE,
            "Edit "
                + number
                + " matches "
                + found.size()
                + " places in the code (it starts with `"
                + shorten(first)
                + "`), so it is not clear where it belongs. Nothing was applied.");
      LineMatch.Found at = found.get(0);
      List<String> replacement =
          edit.replace().isEmpty() ? new ArrayList<>() : LineMatch.split(edit.replace());
      if (at.tier() == 2)
        replacement =
            LineMatch.shiftIndent(
                replacement,
                LineMatch.firstIndent(lines.subList(at.index(), at.index() + search.size())),
                LineMatch.firstIndent(search));
      List<String> next = new ArrayList<>(lines.subList(0, at.index()));
      next.addAll(replacement);
      next.addAll(lines.subList(at.index() + search.size(), lines.size()));
      lines = next;
    }
    return String.join("\n", lines);
  }

  private static String shorten(String s) {
    return s.length() > 60 ? s.substring(0, 57) + "..." : s;
  }
}
