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
 * A long run of lines to delete or replace is written as a RANGE block instead, which names only
 * its first and last line (the model does not have to copy a hundred lines to remove them):
 *
 * <pre>
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; RANGE
 * FROM: the first line of the range, copied
 * TO: the last line of the range, copied
 * WHICH: 2          (only when the first line is in several places: 2, or last)
 * =======
 * what the lines become (nothing at all deletes them)
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * </pre>
 *
 * {@code FROM: START OF CODE} and {@code TO: END OF CODE} stand for the ends of the code that was
 * sent. {@code TO} is the first such line at or after {@code FROM}.
 *
 * <p>Small models drop, shorten or re-indent code when they have to write all of it again; copying
 * a few lines and changing some is much easier for them. The lines are found by content, never by
 * line number, so a model that cannot count does no harm. All or nothing: if one block cannot be
 * placed unambiguously, nothing is applied.
 */
public final class EditBlocks {
  private EditBlocks() {}

  /**
   * {@code anchor} is optional (null or blank for none): a line that appears once in the code and
   * says where {@code search} belongs, usually the signature of the method it is in. When given it
   * must match in exactly one place, and {@code search} is then only looked for inside the method
   * (or, for a line that is not a declaration, after that line).
   */
  public record Edit(String search, String replace, String anchor, Range range) {
    public Edit(String search, String replace, String anchor) {
      this(search, replace, anchor, null);
    }

    public Edit(String search, String replace) {
      this(search, replace, null, null);
    }

    /** A RANGE edit: the lines from {@code from} to {@code to} become {@code replace}. */
    public static Edit range(String from, String to, String which, String replace) {
      return new Edit("", replace, null, new Range(from, to, which));
    }

    boolean hasAnchor() {
      return anchor != null && !anchor.isBlank();
    }
  }

  /**
   * The first and last line of a RANGE edit, as the model wrote them. {@code which} (null for none)
   * says which of several places the first line is in: a number from 1, or "last".
   */
  public record Range(String from, String to, String which) {
    static final String START = "START OF CODE";
    static final String END = "END OF CODE";

    boolean fromStart() {
      return from.strip().equalsIgnoreCase(START);
    }

    boolean toEnd() {
      return to.strip().equalsIgnoreCase(END);
    }
  }

  /** How edit number {@code edit} (from 1) was placed. */
  public record Anchor(int edit, AnchorRung rung) {}

  /** The text with every edit made, and how each edit was found. */
  public record Applied(String text, List<Anchor> anchors) {}

  /** {@code explanation} is the text around the blocks. */
  public record Parsed(List<Edit> edits, String explanation) {}

  private static final Pattern SEARCH =
      Pattern.compile("^\\s*<{5,}\\s*(SEARCH|RANGE)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FIELD =
      Pattern.compile("^\\s*(FROM|TO|WHICH)\\s*:\\s?(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern DIVIDER = Pattern.compile("^\\s*={5,}\\s*$");
  private static final Pattern REPLACE =
      Pattern.compile("^\\s*>{5,}\\s*REPLACE\\s*$", Pattern.CASE_INSENSITIVE);

  /** True when the reply contains at least one SEARCH or RANGE marker. */
  public static boolean looksLike(String reply) {
    for (String line : LineMatch.split(reply)) if (SEARCH.matcher(line).matches()) return true;
    return false;
  }

  public static Parsed parse(String reply) throws AiException {
    return parse(reply, null);
  }

  /**
   * As {@link #parse(String)}. {@code original}, the text the edits are for, settles a block that
   * has more than one line that could be its divider: in Markdown a line of {@code =} also
   * underlines a heading, so {@code Install / ======= / text / ======= / ...} has two. The divider
   * is then the one that leaves the longest SEARCH part found in {@code original} in one place
   * only; with no such split (or no {@code original}) it is the first, as it always was.
   */
  public static Parsed parse(String reply, String original) throws AiException {
    List<String> originalLines = original == null ? null : LineMatch.split(original);
    List<Edit> edits = new ArrayList<>();
    List<String> outside = new ArrayList<>();
    List<String> block = null;
    boolean dividerSeen = false;
    boolean range = false;
    for (String line : LineMatch.split(reply)) {
      if (block == null) { // outside a block
        java.util.regex.Matcher open = SEARCH.matcher(line);
        if (open.matches()) {
          block = new ArrayList<>();
          dividerSeen = false;
          range = open.group(1).equalsIgnoreCase("RANGE");
        } else if (!line.strip().startsWith("```") && !line.strip().startsWith("~~~"))
          outside.add(line);
      } else if (dividerSeen && REPLACE.matcher(line).matches()) {
        edits.add(range ? splitRange(block) : split(block, originalLines));
        block = null;
      } else {
        if (DIVIDER.matcher(line).matches()) dividerSeen = true;
        block.add(line);
      }
    }
    if (block != null)
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.TRUNCATED,
          "The reply stops inside an edit block (it was probably cut off), so nothing is proposed."
              + " Its text is in the AI panel.");
    if (edits.isEmpty())
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.NO_EDIT_BLOCK,
          "The reply has no complete edit block, so there is no change to propose. Its text is in"
              + " the AI panel.");
    return new Parsed(edits, String.join("\n", outside).strip());
  }

  /** The RANGE edit a block holds: FROM / TO / WHICH lines, a divider, and the replacement. */
  private static Edit splitRange(List<String> block) throws AiException {
    int divider = 0;
    while (!DIVIDER.matcher(block.get(divider)).matches()) divider++;
    String from = null;
    String to = null;
    String which = null;
    for (String line : block.subList(0, divider)) {
      if (line.isBlank()) continue;
      java.util.regex.Matcher m = FIELD.matcher(line);
      if (!m.matches())
        throw refused(
            FailureReason.UNPARSEABLE,
            "A RANGE block may only hold FROM:, TO: and WHICH: lines above its =======, but it has"
                + " `"
                + shorten(line.strip())
                + "`. Nothing was applied.");
      String value = m.group(2);
      switch (m.group(1).toUpperCase(java.util.Locale.ROOT)) {
        case "FROM" -> from = value;
        case "TO" -> to = value;
        default -> which = value.strip();
      }
    }
    if (from == null || from.isBlank() || to == null || to.isBlank())
      throw refused(
          FailureReason.UNPARSEABLE,
          "A RANGE block needs a FROM: line and a TO: line that say where the range starts and"
              + " ends (or START OF CODE / END OF CODE). Nothing was applied.");
    return Edit.range(
        from,
        to,
        which == null || which.isEmpty() ? null : which,
        String.join("\n", block.subList(divider + 1, block.size())));
  }

  /** The edit a block holds: the lines before its divider and the lines after it. */
  private static Edit split(List<String> block, List<String> originalLines) {
    List<Integer> dividers = new ArrayList<>();
    for (int i = 0; i < block.size(); i++)
      if (DIVIDER.matcher(block.get(i)).matches()) dividers.add(i);
    int chosen = dividers.get(0);
    if (dividers.size() > 1 && originalLines != null) {
      for (int k = dividers.size() - 1; k >= 0; k--) {
        int at = dividers.get(k);
        if (at == 0) continue;
        List<String> search = block.subList(0, at);
        if (search.stream().allMatch(String::isBlank)) continue;
        if (LineMatch.candidates(originalLines, search, 0, originalLines.size(), LineMatch.MAX_TIER)
                .size()
            == 1) {
          chosen = at;
          break;
        }
      }
    }
    return new Edit(
        String.join("\n", block.subList(0, chosen)),
        String.join("\n", block.subList(chosen + 1, block.size())));
  }

  /**
   * The text with every edit made, one after the other (a later edit sees the result of an earlier
   * one).
   */
  public static String apply(String original, List<Edit> edits) throws AiException {
    return applyWithAnchors(original, edits).text();
  }

  /**
   * Like {@link #apply} and also says, for each edit, which rung of the {@link AnchorRung} ladder
   * found its lines. An edit whose lines are in no place or in several is refused with a reason,
   * and then nothing is applied: a place is never guessed.
   */
  public static Applied applyWithAnchors(String original, List<Edit> edits) throws AiException {
    List<String> lines = LineMatch.split(original);
    List<Anchor> anchors = new ArrayList<>();
    int number = 0;
    java.util.Set<Edit> done = new java.util.HashSet<>();
    for (Edit edit : edits) {
      number++;
      if (edit.range() != null) {
        if (!done.add(edit)) {
          anchors.add(new Anchor(number, AnchorRung.ALREADY_APPLIED));
          continue;
        }
        RangePlace place = placeRange(lines, edit.range(), number);
        List<String> replacement =
            edit.replace().isEmpty() ? new ArrayList<>() : LineMatch.split(edit.replace());
        if (place.rung() != AnchorRung.EXACT)
          replacement =
              LineMatch.shiftIndent(
                  replacement,
                  LineMatch.firstIndent(lines.subList(place.at(), place.at() + place.length())),
                  place.writtenIndent());
        lines = splice(lines, place.at(), place.length(), replacement);
        anchors.add(new Anchor(number, place.rung()));
        continue;
      }
      List<String> search = LineMatch.split(edit.search());
      if (!edit.search().isBlank() && !done.add(edit)) {
        // the very same edit again: making it twice would double its lines
        anchors.add(new Anchor(number, AnchorRung.ALREADY_APPLIED));
        continue;
      }
      if (edit.search().isBlank())
        throw refused(
            FailureReason.UNPARSEABLE,
            "Edit "
                + number
                + " has an empty SEARCH part, so it does not say which lines it changes. Nothing"
                + " was applied.");
      int from = 0;
      int to = lines.size();
      if (edit.hasAnchor()) {
        CodeSpans.Span scope = scopeOf(lines, edit.anchor(), number);
        from = scope.first();
        to = scope.last() + 1;
      }
      List<LineMatch.Found> found =
          LineMatch.candidates(lines, search, from, to, LineMatch.MAX_TIER);
      String first = search.stream().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
      List<String> replacement =
          edit.replace().isEmpty() ? new ArrayList<>() : LineMatch.split(edit.replace());
      int at;
      int length;
      AnchorRung rung;
      if (found.isEmpty() && alreadyThere(lines, replacement, from, to)) {
        anchors.add(new Anchor(number, AnchorRung.ALREADY_APPLIED));
        continue;
      }
      if (found.isEmpty()) {
        Signature bySignature = edit.hasAnchor() ? null : bySignature(lines, search, replacement);
        if (bySignature == null)
          throw refused(
              FailureReason.ANCHOR_NOT_FOUND,
              "Edit "
                  + number
                  + " searches for lines that are not in the code"
                  + (edit.hasAnchor() ? " (or not in the place its anchor names)" : "")
                  + " (it starts with `"
                  + shorten(first)
                  + "`), so nothing was applied. The model probably changed or invented them.");
        at = bySignature.first();
        length = bySignature.length();
        rung = AnchorRung.SIGNATURE;
      } else if (found.size() > 1) {
        throw refused(
            FailureReason.ANCHOR_NOT_FOUND,
            "Edit "
                + number
                + " matches "
                + found.size()
                + " places in the code, at lines "
                + found.stream()
                    .map(f -> String.valueOf(f.index() + 1))
                    .collect(java.util.stream.Collectors.joining(", "))
                + " (it starts with `"
                + shorten(first)
                + "`), so it is not clear where it belongs. Nothing was applied. Add the line above"
                + " or below it that is different in each place, so it matches in one place only.");
      } else {
        at = found.get(0).index();
        length = search.size();
        rung = found.get(0).rung();
      }
      if (rung != AnchorRung.EXACT)
        replacement =
            LineMatch.shiftIndent(
                replacement,
                LineMatch.firstIndent(lines.subList(at, at + length)),
                LineMatch.firstIndent(search));
      lines = splice(lines, at, length, replacement);
      anchors.add(new Anchor(number, rung));
    }
    return new Applied(String.join("\n", lines), anchors);
  }

  private static List<String> splice(
      List<String> lines, int at, int length, List<String> replacement) {
    List<String> next = new ArrayList<>(lines.subList(0, at));
    next.addAll(replacement);
    next.addAll(lines.subList(at + length, lines.size()));
    return next;
  }

  private record RangePlace(int at, int length, AnchorRung rung, String writtenIndent) {}

  /**
   * Where a RANGE edit's lines are: from the line FROM names (the one {@code which} picks when
   * there are several) to the first line at or after it that TO names. A place is never guessed: a
   * FROM or TO that is in no place, or a FROM that is in several with no WHICH, refuses the edit,
   * and so everything.
   */
  private static RangePlace placeRange(List<String> lines, Range range, int number)
      throws AiException {
    int n = lines.size();
    int first;
    int tier = 0;
    if (range.fromStart()) {
      first = 0;
    } else {
      List<LineMatch.Found> found =
          LineMatch.candidates(lines, List.of(range.from()), 0, n, LineMatch.MAX_TIER);
      if (found.isEmpty())
        throw refused(
            FailureReason.ANCHOR_NOT_FOUND,
            "Edit "
                + number
                + " is a RANGE that starts at a line that is not in the code (`"
                + shorten(range.from().strip())
                + "`), so nothing was applied. The model probably changed or invented it.");
      int pick = 0;
      if (found.size() > 1) {
        pick = whichOf(range.which(), found.size());
        if (pick == -2)
          throw refused(
              FailureReason.UNPARSEABLE,
              "Edit "
                  + number
                  + " has a WHICH: line that is neither a number nor `last` (`"
                  + shorten(range.which())
                  + "`). Nothing was applied.");
        if (pick < 0)
          throw refused(
              FailureReason.ANCHOR_NOT_FOUND,
              "Edit "
                  + number
                  + " is a RANGE whose first line (`"
                  + shorten(range.from().strip())
                  + "`) is in "
                  + found.size()
                  + " places, at lines "
                  + found.stream()
                      .map(f -> String.valueOf(f.index() + 1))
                      .collect(java.util.stream.Collectors.joining(", "))
                  + ", so it is not clear which is meant. Nothing was applied. Add a line `WHICH:"
                  + " 2` (or `WHICH: last`) to the block to say which one.");
      }
      first = found.get(pick).index();
      tier = found.get(pick).tier();
    }
    int last;
    if (range.toEnd()) {
      last = n - 1;
      if (last > first && lines.get(last).isEmpty()) last--; // keep the final line break
    } else {
      List<LineMatch.Found> after =
          LineMatch.candidates(lines, List.of(range.to()), first, n, LineMatch.MAX_TIER);
      if (after.isEmpty())
        throw refused(
            FailureReason.ANCHOR_NOT_FOUND,
            "Edit "
                + number
                + " is a RANGE whose last line (`"
                + shorten(range.to().strip())
                + "`) is not in the code after its first line (line "
                + (first + 1)
                + "), so nothing was applied.");
      last = after.get(0).index();
      tier = Math.max(tier, after.get(0).tier());
    }
    return new RangePlace(
        first, last - first + 1, AnchorRung.ofTier(tier), LineMatch.indentOf(range.from()));
  }

  /**
   * The 0-based choice a WHICH value makes among {@code count} places: -1 when there is none, -2
   * when it cannot be read, and the choice, or -1, when it is out of range.
   */
  private static int whichOf(String which, int count) {
    if (which == null || which.isBlank()) return -1;
    String w = which.strip().toLowerCase(java.util.Locale.ROOT);
    int number;
    if (w.contains("last")) number = count;
    else if (w.contains("first")) number = 1;
    else if (w.contains("second")) number = 2;
    else if (w.contains("third")) number = 3;
    else {
      java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d+").matcher(w);
      if (!digits.find()) return -2;
      number = Integer.parseInt(digits.group());
    }
    return number >= 1 && number <= count ? number - 1 : -1;
  }

  /**
   * True when the replacement (some real text, not a deletion) is in the code exactly once: an
   * earlier edit already made this change.
   */
  private static boolean alreadyThere(
      List<String> lines, List<String> replacement, int from, int to) {
    int first = 0;
    int last = replacement.size();
    while (first < last && replacement.get(first).isBlank()) first++;
    while (last > first && replacement.get(last - 1).isBlank()) last--;
    if (first == last) return false;
    return LineMatch.candidates(lines, replacement.subList(first, last), from, to, 0).size() == 1;
  }

  private static AiException refused(FailureReason reason, String message) {
    return new AiException(AiException.Kind.BAD_RESPONSE, reason, message);
  }

  /**
   * Where an edit's anchor line says its lines are: the method the anchor starts, or, for an anchor
   * that is not a declaration, everything from that line on. The anchor line must be in the code
   * once, ignoring differences in spaces.
   */
  private static CodeSpans.Span scopeOf(List<String> lines, String anchor, int number)
      throws AiException {
    String key = CodeSpans.signatureKey(anchor.lines().findFirst().orElse(anchor));
    List<Integer> at = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++)
      if (CodeSpans.signatureKey(lines.get(i)).equals(key) && !key.isEmpty()) at.add(i);
    if (at.isEmpty())
      throw refused(
          FailureReason.ANCHOR_NOT_FOUND,
          "Edit "
              + number
              + " names an anchor line that is not in the code (`"
              + shorten(key)
              + "`), so nothing was applied.");
    if (at.size() > 1)
      throw refused(
          FailureReason.ANCHOR_NOT_FOUND,
          "Edit "
              + number
              + " names an anchor line that appears "
              + at.size()
              + " times in the code (`"
              + shorten(key)
              + "`), so it does not say where the edit belongs. Nothing was applied.");
    int line = at.get(0);
    if (CodeSpans.declaredName(lines.get(line)).isPresent()) {
      var span = CodeSpans.declarationSpan(lines, line);
      if (span.isPresent()) return span.get();
    }
    return new CodeSpans.Span(line, lines.size() - 1);
  }

  private record Signature(int first, int length) {}

  /**
   * The last rung: the SEARCH part is a whole method (it starts with a declaration and its braces
   * balance to the last line), the lines are not in the code as written, but a method with that
   * signature is declared exactly once, and the REPLACE part is a whole method of the same name.
   * Then the method in the code is the one that is meant. Anything less clear than that is not
   * guessed at.
   */
  private static Signature bySignature(
      List<String> lines, List<String> search, List<String> replace) {
    int declaration = -1;
    int annotations = 0;
    for (int i = 0; i < search.size() && declaration < 0; i++) {
      String line = search.get(i);
      if (line.isBlank()) continue;
      if (CodeSpans.isAnnotationOnly(line)) annotations++;
      else if (CodeSpans.declaredName(line).isPresent()) declaration = i;
      else return null;
    }
    if (declaration < 0) return null;
    var inSearch = CodeSpans.declarationSpan(search, declaration);
    int lastText = search.size() - 1;
    while (lastText >= 0 && search.get(lastText).isBlank()) lastText--;
    if (inSearch.isEmpty() || inSearch.get().last() != lastText) return null;
    String name = CodeSpans.declaredName(search.get(declaration)).orElseThrow();
    int replaceDeclaration = -1;
    for (int i = 0; i < replace.size() && replaceDeclaration < 0; i++) {
      String line = replace.get(i);
      if (line.isBlank() || CodeSpans.isAnnotationOnly(line)) continue;
      if (CodeSpans.declaredName(line).isPresent()) replaceDeclaration = i;
      else return null;
    }
    if (replaceDeclaration < 0
        || !CodeSpans.declaredName(replace.get(replaceDeclaration)).orElseThrow().equals(name))
      return null;
    String key = CodeSpans.signatureKey(search.get(declaration));
    int found = -1;
    for (int i = 0; i < lines.size(); i++)
      if (CodeSpans.signatureKey(lines.get(i)).equals(key)) {
        if (found >= 0) return null;
        found = i;
      }
    if (found < 0) return null;
    var inCode = CodeSpans.declarationSpan(lines, found);
    if (inCode.isEmpty()) return null;
    int first = found - annotations;
    if (first < 0) return null;
    for (int i = first; i < found; i++) if (!CodeSpans.isAnnotationOnly(lines.get(i))) return null;
    return new Signature(first, inCode.get().last() - first + 1);
  }

  private static String shorten(String s) {
    return s.length() > 60 ? s.substring(0, 57) + "..." : s;
  }
}
