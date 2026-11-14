package com.javelin.ai;

import java.util.List;
import java.util.Locale;

/**
 * What the AI pipeline needs to know about Markdown, kept in one place so the reply parser, the
 * checks and the prompts agree. A Markdown file is different from code in three ways that matter to
 * an edit: it contains code fences of its own (so a reply that wraps the file in a fence has fences
 * inside the fence), its "markers" are ordinary text (a heading may be called "Other methods", and
 * a line of {@code =} underlines a heading), and its structure is lines, not brackets. JavaFX-free.
 */
public final class MarkdownText {
  private MarkdownText() {}

  /**
   * True for a Markdown file: by language id when the caller has one, else by the file's name.
   * Neither is needed for a document that is not named (it is then treated as code, as before).
   */
  public static boolean is(String languageId, String fileName) {
    if (languageId != null) {
      String id = languageId.toLowerCase(Locale.ROOT);
      if (id.equals("markdown") || id.equals("md")) return true;
    }
    if (fileName == null) return false;
    String name = fileName.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdx");
  }

  /** The word after an opening fence ({@code markdown} in {@code ```markdown}), or "". */
  static String fenceInfo(String line) {
    String t = line.strip();
    int n = 0;
    while (n < t.length() && (t.charAt(n) == '`' || t.charAt(n) == '~')) n++;
    return n < 3 ? "" : t.substring(n).strip().toLowerCase(Locale.ROOT);
  }

  /** True when a fence's info string says its content is itself Markdown. */
  static boolean isMarkdownInfo(String info) {
    String word = info.split("[\\s{]", 2)[0];
    return word.equals("markdown") || word.equals("md") || word.equals("gfm");
  }

  /**
   * How many fenced code blocks are left open at the end of {@code text}: 0 for a balanced
   * document, 1 when a fence was opened and never closed. A fence is closed only by a bare fence of
   * the same character and at least the same length, as in CommonMark.
   */
  public static int openFences(String text) {
    char open = 0;
    int length = 0;
    for (String line : LineMatch.split(text)) {
      String t = indent(line);
      if (t == null) continue;
      int n = run(t);
      if (n < 3) continue;
      char c = t.charAt(0);
      if (open == 0) {
        if (c == '`' && t.indexOf('`', n) >= 0) continue; // ```inline``` is not a fence
        open = c;
        length = n;
      } else if (c == open && n >= length && t.substring(n).isBlank()) {
        open = 0;
      }
    }
    return open == 0 ? 0 : 1;
  }

  /** The lines of a leading {@code ---} front matter block, or empty when there is none. */
  public static List<String> frontMatter(String text) {
    List<String> lines = LineMatch.split(text);
    if (lines.isEmpty() || !lines.get(0).stripTrailing().equals("---")) return List.of();
    for (int i = 1; i < lines.size(); i++) {
      String t = lines.get(i).stripTrailing();
      if (t.equals("---") || t.equals("...")) return List.copyOf(lines.subList(0, i + 1));
    }
    return List.of();
  }

  /** True for a line of {@code =} or {@code -} that underlines the line above it as a heading. */
  static boolean isSetextUnderline(String line) {
    String t = line.strip();
    if (t.length() < 2) return false;
    char c = t.charAt(0);
    if (c != '=' && c != '-') return false;
    for (int i = 1; i < t.length(); i++) if (t.charAt(i) != c) return false;
    return true;
  }

  private static String indent(String line) {
    int spaces = 0;
    while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
    return spaces > 3 ? null : line.substring(spaces);
  }

  private static int run(String t) {
    if (t.isEmpty() || (t.charAt(0) != '`' && t.charAt(0) != '~')) return 0;
    int n = 0;
    while (n < t.length() && t.charAt(n) == t.charAt(0)) n++;
    return n;
  }
}
