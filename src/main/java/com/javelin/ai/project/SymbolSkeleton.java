package com.javelin.ai.project;

import com.javelin.ai.SourceOutline.Member;
import com.javelin.ai.SourceOutline.Outline;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a Java file as a view: its package and imports, its types, its fields and the signature of
 * every method, with the real text of the methods that were asked for in full. A method that is not
 * in full keeps its annotations and its declaration exactly as written, and its body becomes the
 * placeholder {@value #PLACEHOLDER}. Comments and blank lines are left out, except the comment
 * written directly above a method that is shown in full. A view is only made for a file whose
 * outline is complete ({@link Outline#isComplete}), so nothing but bodies and comments is ever
 * missing from it.
 */
final class SymbolSkeleton {
  static final String PLACEHOLDER = "{ /* body not shown */ }";

  /** The view, and what it shows of each member. */
  record Rendered(String text, List<ShownSymbol> shown) {}

  /** A type that is open while its members are written. */
  private static final class Frame {
    final Member type;
    final String indent;
    int signatures;
    int skipped;

    Frame(Member type, String indent) {
      this.type = type;
      this.indent = indent;
    }
  }

  private final List<String> lines;
  private final Set<Integer> full;
  private final Map<Integer, String> why;
  private final int maxSignaturesPerType;
  private final StringBuilder out = new StringBuilder();
  private final List<ShownSymbol> shown = new ArrayList<>();
  private final Deque<Frame> open = new ArrayDeque<>();

  private SymbolSkeleton(
      String text, Set<Integer> full, Map<Integer, String> why, int maxSignaturesPerType) {
    this.lines = text.lines().toList();
    this.full = full;
    this.why = why;
    this.maxSignaturesPerType = maxSignaturesPerType;
  }

  /**
   * @param full the first lines ({@link Member#first}) of the members shown in full
   * @param why what to say about a member, by first line: why it is in full, or why only its
   *     signature is shown
   * @param maxSignaturesPerType the most signature lines listed under one type; the rest are
   *     reported as not shown
   */
  static Rendered render(
      String text,
      Outline outline,
      Set<Integer> full,
      Map<Integer, String> why,
      int maxSignaturesPerType) {
    return new SymbolSkeleton(text, full, why, maxSignaturesPerType).write(outline);
  }

  private Rendered write(Outline outline) {
    boolean afterPackage = false;
    for (String line : lines) {
      String t = line.strip();
      boolean isImport = t.startsWith("import ");
      if (afterPackage && isImport) out.append('\n');
      afterPackage = false;
      if (t.startsWith("package ") || isImport) out.append(line.stripTrailing()).append('\n');
      afterPackage = t.startsWith("package ");
    }
    out.append('\n');
    for (Member m : outline.members()) {
      while (!open.isEmpty() && m.first() > open.peek().type.last()) close();
      switch (m.kind()) {
        case TYPE -> type(m);
        case FIELD -> {
          if (room(m)) {
            verbatim(m.first(), m.last());
            shown.add(new ShownSymbol(m.qualified(), ShownSymbol.SIGNATURE, "field"));
          }
        }
        default -> callable(m);
      }
    }
    while (!open.isEmpty()) close();
    return new Rendered(out.toString().stripTrailing() + "\n", shown);
  }

  private void type(Member m) {
    verbatim(m.first(), m.hasBody() ? m.open() : m.last());
    shown.add(
        new ShownSymbol(
            m.qualified(), ShownSymbol.SIGNATURE, why.getOrDefault(m.first(), "declaration")));
    boolean oneLine = m.first() == m.last() || !m.hasBody();
    if (!oneLine) open.push(new Frame(m, indentOf(lines.get(m.open() - 1))));
  }

  private void callable(Member m) {
    if (full.contains(m.first())) {
      int start = m.first();
      while (start > 1 && isComment(lines.get(start - 2))) start--; // its documentation comment
      verbatim(start, m.last());
      out.append('\n');
      shown.add(
          new ShownSymbol(m.qualified(), ShownSymbol.IN_FULL, why.getOrDefault(m.first(), "")));
      return;
    }
    if (!room(m)) return;
    if (!m.hasBody()) verbatim(m.first(), m.last());
    else {
      verbatim(m.first(), m.open() - 1);
      String openLine = lines.get(m.open() - 1);
      int brace = openLine.indexOf('{');
      out.append(openLine, 0, brace < 0 ? openLine.length() : brace)
          .append(brace < 0 ? "" : PLACEHOLDER)
          .append('\n');
    }
    shown.add(
        new ShownSymbol(
            m.qualified(),
            ShownSymbol.SIGNATURE,
            why.getOrDefault(m.first(), "its body is not needed for this request")));
  }

  /** True when the type the member is in has room for one more signature line. */
  private boolean room(Member m) {
    if (open.isEmpty()) return true;
    Frame f = open.peek();
    if (f.signatures >= maxSignaturesPerType) {
      f.skipped++;
      shown.add(
          new ShownSymbol(
              m.qualified(), ShownSymbol.NOT_SHOWN, "more members than this profile lists"));
      return false;
    }
    f.signatures++;
    return true;
  }

  private void close() {
    Frame f = open.pop();
    if (f.skipped > 0)
      out.append(f.indent)
          .append("    // ")
          .append(f.skipped)
          .append(" more member(s) not shown\n");
    out.append(f.indent).append("}\n");
  }

  private void verbatim(int first, int last) {
    for (int n = first; n <= last && n <= lines.size(); n++)
      out.append(lines.get(n - 1).stripTrailing()).append('\n');
  }

  private static boolean isComment(String line) {
    String t = line.strip();
    return t.startsWith("/*") || t.startsWith("*") || t.startsWith("//");
  }

  private static String indentOf(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
    return line.substring(0, i);
  }
}
