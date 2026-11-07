package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a unified diff (what {@code git diff} prints, or what a model writes in a {@code diff}
 * block) to one text.
 *
 * <p>{@code git apply} and {@code patch} are exact about the numbers in the {@code @@} lines, and a
 * model that writes a diff gets those numbers wrong most of the time. This applier uses them only
 * as a hint for which of several equal places is meant: a hunk is found by its context and removed
 * lines (exactly, then ignoring spaces at line ends, then ignoring indentation), counts are
 * ignored, and a hunk that cannot be placed stops everything, so a patch is never half applied.
 * File headers ({@code ---}, {@code +++}, {@code diff --git}) are skipped: the patch applies to the
 * text it is given.
 */
public final class UnifiedPatch {
  private UnifiedPatch() {}

  private static final Pattern HUNK =
      Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@.*$");

  /** True when the text has at least one proper {@code @@ -a,b +c,d @@} line. */
  public static boolean looksLike(String text) {
    for (String line : LineMatch.split(text)) if (HUNK.matcher(line).matches()) return true;
    return false;
  }

  private record Hunk(int expectedLine, List<String> before, List<String> after) {}

  public static String apply(String original, String patch) throws AiException {
    List<Hunk> hunks = parse(patch);
    if (hunks.isEmpty())
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.UNPARSEABLE,
          "The patch has no hunks (no line that starts with @@), so there is no change to"
              + " propose.");
    List<String> lines = LineMatch.split(original);
    int cursor = 0, drift = 0, number = 0;
    for (Hunk hunk : hunks) {
      number++;
      int expected = Math.max(0, hunk.expectedLine() + drift);
      int at;
      int tier = 0;
      if (hunk.before().isEmpty()) {
        at = Math.min(expected, lines.size());
      } else {
        List<LineMatch.Found> found = LineMatch.candidates(lines, hunk.before(), cursor);
        if (found.isEmpty()) {
          String first =
              hunk.before().stream().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
          throw new AiException(
              AiException.Kind.BAD_RESPONSE,
              FailureReason.ANCHOR_NOT_FOUND,
              "Hunk "
                  + number
                  + " of the patch does not fit the code (it expects `"
                  + (first.length() > 60 ? first.substring(0, 57) + "..." : first)
                  + "`), so nothing was applied. The model probably changed or invented the lines"
                  + " it shows.");
        }
        LineMatch.Found best = found.get(0);
        for (LineMatch.Found f : found)
          if (Math.abs(f.index() - expected) < Math.abs(best.index() - expected)) best = f;
        at = best.index();
        tier = best.tier();
      }
      List<String> after = hunk.after();
      if (tier == 2)
        after =
            LineMatch.shiftIndent(
                after,
                LineMatch.firstIndent(lines.subList(at, at + hunk.before().size())),
                LineMatch.firstIndent(hunk.before()));
      List<String> next = new ArrayList<>(lines.subList(0, at));
      next.addAll(after);
      next.addAll(lines.subList(at + hunk.before().size(), lines.size()));
      lines = next;
      cursor = at + after.size();
      drift += after.size() - hunk.before().size();
    }
    return String.join("\n", lines);
  }

  private static List<Hunk> parse(String patch) {
    List<String> input = LineMatch.split(patch);
    List<Hunk> hunks = new ArrayList<>();
    List<String> before = null, after = null;
    int expected = 0;
    for (int i = 0; i < input.size(); i++) {
      String line = input.get(i);
      Matcher m = HUNK.matcher(line);
      if (m.matches()) {
        if (before != null) hunks.add(new Hunk(expected, before, after));
        before = new ArrayList<>();
        after = new ArrayList<>();
        expected = Math.max(0, Integer.parseInt(m.group(1)) - 1);
        continue;
      }
      if (before == null) continue; // file headers and anything else before the first hunk
      if (line.startsWith("diff ") || line.startsWith("index ")) continue;
      if (line.startsWith("--- ") && i + 1 < input.size() && input.get(i + 1).startsWith("+++ ")) {
        i++;
        continue;
      }
      if (line.startsWith("\\")) continue; // "\ No newline at end of file"
      if (line.startsWith("+")) after.add(line.substring(1));
      else if (line.startsWith("-")) before.add(line.substring(1));
      else {
        String text =
            line.startsWith(" ")
                ? line.substring(1)
                : line; // a model often drops the space in front of context
        before.add(text);
        after.add(text);
      }
    }
    if (before != null) hunks.add(new Hunk(expected, before, after));
    // a trailing empty line is the end of the text, not a context line
    for (Hunk h : hunks) trimTrailingBlankContext(h);
    return hunks;
  }

  private static void trimTrailingBlankContext(Hunk h) {
    while (!h.before().isEmpty()
        && !h.after().isEmpty()
        && h.before().get(h.before().size() - 1).isEmpty()
        && h.after().get(h.after().size() - 1).isEmpty()
        && h.before().size() > 1
        && h.after().size() > 1) {
      h.before().remove(h.before().size() - 1);
      h.after().remove(h.after().size() - 1);
    }
  }
}
