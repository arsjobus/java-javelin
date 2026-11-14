package com.javelin.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a good test of a piece of code has to hit, read from the code itself and put into the
 * request for tests. A small model asked to "cover normal cases, edge cases and failures" writes
 * the obvious cases, misses the value where the code changes its mind (a limit of {@code >= 5}
 * needs a test with exactly 5 items) and asserts failures nobody wrote. Finding those places is
 * mechanical, so it is done here and the model is told where they are. Nothing here judges code or
 * runs it, and it answers nothing when it finds nothing. JavaFX-free.
 */
final class TestTargets {
  private TestTargets() {}

  private static final int MAX_ITEMS = 6;
  private static final int MAX_DOC_CHARS = 220;

  private static final Pattern PACKAGE =
      Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
  private static final Pattern TYPE =
      Pattern.compile(
          "^\\s*(?:(?:public|final|abstract|sealed|static)\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)",
          Pattern.MULTILINE);
  private static final Pattern COMPARISON =
      Pattern.compile("(<=|>=|<|>)\\s*(-?\\d{1,9})\\b|\\b(-?\\d{1,9})\\s*(<=|>=|<|>)(?!=)");
  private static final Pattern MODULO = Pattern.compile("%\\s*(\\d{1,4})\\b");
  private static final Pattern THROW =
      Pattern.compile("throw\\s+new\\s+([A-Za-z_$][\\w$.]*)\\s*\\(");
  private static final Pattern JAVADOC = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);

  /** The package a Java source declares, or null. */
  static String packageOf(String source) {
    Matcher m = PACKAGE.matcher(source);
    return m.find() ? m.group(1) : null;
  }

  /** The first type a Java source declares, or null. */
  static String typeOf(String source) {
    Matcher m = TYPE.matcher(source);
    return m.find() ? m.group(1) : null;
  }

  /**
   * Places where a value decides the result, as sentences ready for a prompt: each comparison with
   * a number (test just below, at and just above it) and each {@code %} (test a multiple, a
   * non-multiple and the common multiples of the numbers used).
   */
  static List<String> boundaries(String source) {
    Set<String> found = new LinkedHashSet<>();
    List<Integer> moduli = new ArrayList<>();
    for (String raw : source.split("\n", -1)) {
      String line = code(raw);
      if (line.isBlank()) continue;
      Matcher mod = MODULO.matcher(line);
      boolean hasModulo = false;
      while (mod.find()) {
        hasModulo = true;
        int k = Integer.parseInt(mod.group(1));
        if (k > 1 && !moduli.contains(k)) moduli.add(k);
      }
      Matcher cmp = COMPARISON.matcher(line);
      while (cmp.find() && !hasModulo) {
        String op = cmp.group(1) != null ? cmp.group(1) : cmp.group(4);
        int n = Integer.parseInt(cmp.group(2) != null ? cmp.group(2) : cmp.group(3));
        found.add(
            "`"
                + cmp.group().strip()
                + "` (in `"
                + shorten(display(raw))
                + "`): test "
                + (n - 1)
                + ", "
                + n
                + " and "
                + (n + 1)
                + ". A `"
                + (op.contains("=") ? op.substring(0, 1) : op + "=")
                + "` that should be `"
                + op
                + "` (or the reverse) is only caught by the test at exactly "
                + n
                + ".");
      }
    }
    if (!moduli.isEmpty()) {
      Set<Integer> values = new LinkedHashSet<>(moduli);
      for (int a = 0; a < moduli.size(); a++)
        for (int b = a + 1; b < moduli.size(); b++) {
          int l = lcm(moduli.get(a), moduli.get(b));
          if (l <= 10_000) values.add(l);
        }
      List<String> shown = new ArrayList<>();
      for (int v : values) shown.add(String.valueOf(v));
      found.add(
          "The code divides by "
              + moduli.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
              + " (`%`): test a number that is divisible by each one, a number that is divisible by"
              + " more than one of them, and a number that is divisible by none. Good inputs:"
              + " "
              + String.join(", ", shown)
              + ", and one that is not a multiple of any.");
    }
    return limit(found);
  }

  /** The sentences of the doc comments, one item for each comment. */
  static List<String> documented(String source) {
    Set<String> found = new LinkedHashSet<>();
    Matcher m = JAVADOC.matcher(source);
    while (m.find()) {
      StringBuilder text = new StringBuilder();
      for (String line : m.group(1).split("\n")) {
        String t = line.strip();
        while (t.startsWith("*")) t = t.substring(1).strip();
        if (t.startsWith("@") || t.isEmpty()) continue;
        text.append(text.length() == 0 ? "" : " ").append(t);
      }
      String doc = text.toString().replaceAll("<[^>]+>", "").replaceAll("\\{@code ([^}]*)}", "$1");
      if (doc.isBlank()) continue;
      found.add(doc.length() > MAX_DOC_CHARS ? doc.substring(0, MAX_DOC_CHARS - 3) + "..." : doc);
    }
    return limit(found);
  }

  /** The exceptions the code throws on purpose, with the line that throws them. */
  static List<String> thrown(String source) {
    Set<String> found = new LinkedHashSet<>();
    for (String raw : source.split("\n", -1)) {
      String line = code(raw);
      Matcher m = THROW.matcher(line);
      if (m.find()) found.add("`" + m.group(1) + "` from `" + shorten(display(raw)) + "`");
    }
    return limit(found);
  }

  /**
   * The paragraph that goes into a request for tests, or "" when the code gives nothing to point
   * at.
   */
  static String hints(String source, String fileName, String languageId) {
    StringBuilder out = new StringBuilder();
    if ("java".equals(languageId)) {
      String pkg = packageOf(source);
      String type = typeOf(source);
      if (type != null) {
        out.append("The class under test is `")
            .append(type)
            .append("`")
            .append(pkg == null ? " (no package)" : " in package `" + pkg + "`")
            .append(". Write the tests in ")
            .append(
                pkg == null
                    ? "no package"
                    : "package `" + pkg + "` (start with `package " + pkg + ";`)")
            .append(", in a class named `")
            .append(type)
            .append("Test`, with JUnit 5 only (`org.junit.jupiter.api.Test` and a static import of")
            .append(" `org.junit.jupiter.api.Assertions.*`).\n");
      }
    }
    section(
        out,
        "Documented behavior: write at least one test for each of these, with an input"
            + " on which the sentence changes the result:",
        documented(source));
    section(out, "Values that decide the result:", boundaries(source));
    section(out, "Exceptions the code throws on purpose (test these):", thrown(source));
    if (out.length() == 0) return "";
    return out.toString().stripTrailing();
  }

  private static void section(StringBuilder out, String title, List<String> items) {
    if (items.isEmpty()) return;
    out.append(title).append('\n');
    for (String item : items) out.append("- ").append(item).append('\n');
  }

  /** A line without its trailing {@code //} comment, and without a leading comment marker. */
  private static String code(String raw) {
    String t = raw.strip();
    if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) return "";
    int c = t.indexOf("//");
    String line = c >= 0 ? t.substring(0, c) : t;
    return line.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  /** A line as the person wrote it, without its trailing comment, for showing in a prompt. */
  private static String display(String raw) {
    String t = raw.strip();
    int c = t.indexOf("//");
    return (c >= 0 ? t.substring(0, c) : t).strip();
  }

  private static List<String> limit(Set<String> found) {
    return new ArrayList<>(found).subList(0, Math.min(MAX_ITEMS, found.size()));
  }

  private static String shorten(String s) {
    return s.length() > 90 ? s.substring(0, 87) + "..." : s;
  }

  private static int lcm(int a, int b) {
    return a / gcd(a, b) * b;
  }

  private static int gcd(int a, int b) {
    return b == 0 ? a : gcd(b, a % b);
  }
}
