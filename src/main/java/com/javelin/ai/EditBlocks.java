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

  /**
   * {@code anchor} is optional (null or blank for none): a line that appears once in the code and
   * says where {@code search} belongs, usually the signature of the method it is in. When given it
   * must match in exactly one place, and {@code search} is then only looked for inside the method
   * (or, for a line that is not a declaration, after that line).
   */
  public record Edit(String search, String replace, String anchor) {
    public Edit(String search, String replace) {
      this(search, replace, null);
    }

    boolean hasAnchor() {
      return anchor != null && !anchor.isBlank();
    }
  }

  /** How edit number {@code edit} (from 1) was placed. */
  public record Anchor(int edit, AnchorRung rung) {}

  /** The text with every edit made, and how each edit was found. */
  public record Applied(String text, List<Anchor> anchors) {}

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
    for (String line : LineMatch.split(reply)) {
      if (block == null) { // outside a block
        if (SEARCH.matcher(line).matches()) {
          block = new ArrayList<>();
          dividerSeen = false;
        } else if (!line.strip().startsWith("```") && !line.strip().startsWith("~~~"))
          outside.add(line);
      } else if (dividerSeen && REPLACE.matcher(line).matches()) {
        edits.add(split(block, originalLines));
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
    for (Edit edit : edits) {
      number++;
      List<String> search = LineMatch.split(edit.search());
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
                + " places in the code (it starts with `"
                + shorten(first)
                + "`), so it is not clear where it belongs. Nothing was applied.");
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
      List<String> next = new ArrayList<>(lines.subList(0, at));
      next.addAll(replacement);
      next.addAll(lines.subList(at + length, lines.size()));
      lines = next;
      anchors.add(new Anchor(number, rung));
    }
    return new Applied(String.join("\n", lines), anchors);
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
