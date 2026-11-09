package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers two questions about a source file for the plan steps of Phase 3: which methods does it
 * have, and where does the one called {@code name} start and end. It is {@link CodeSpans} made
 * public: declarations are found by pattern and their bodies by counting braces (comments, strings
 * and text blocks skipped), so it is right for Java and the brace languages and says nothing when
 * it cannot tell. It is not a parser: a method is found by its name, and every overload of that
 * name is returned. JavaFX-free.
 */
public final class MethodFinder {
  private MethodFinder() {}

  /**
   * One method (or constructor) as it stands in the text. {@code firstLine} and {@code lastLine}
   * are 1-based and include the annotations written above it; {@code text} is those lines. A method
   * with no body ({@code abstract}, in an interface) or one whose braces do not balance covers its
   * declaration line only, and {@code hasBody} is false.
   */
  public record Found(
      String name, String signature, int firstLine, int lastLine, boolean hasBody, String text) {}

  /** A declaration of any other language the editor knows: {@code def f}, {@code fn f}... */
  private static final Pattern OTHER_FUNCTION =
      Pattern.compile(
          "(?m)^\\s*(?:(?:export|default|pub|public|private|static|async)\\s+)*"
              + "(?:def|func|function|fn)\\s+([A-Za-z_$][\\w$]*)");

  /** True for a name that can be the name of a method. */
  public static boolean isIdentifier(String name) {
    return name != null && name.matches("[A-Za-z_$][\\w$]*");
  }

  /**
   * Every method or constructor called {@code name}, in file order. Type declarations are not
   * methods, so {@code find(text, "Calc")} finds the constructor of {@code Calc} and not the class.
   */
  public static List<Found> find(String text, String name) {
    List<Found> out = new ArrayList<>();
    if (!isIdentifier(name)) return out;
    List<String> lines = text.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      Optional<String> declared = declaredName(line);
      if (declared.isEmpty() || !declared.get().equals(name) || CodeSpans.isTypeDeclaration(line))
        continue;
      int first = i;
      while (first > 0 && CodeSpans.isAnnotationOnly(lines.get(first - 1))) first--;
      Optional<CodeSpans.Span> span = CodeSpans.declarationSpan(lines, i);
      int last = span.map(CodeSpans.Span::last).orElse(i);
      out.add(
          new Found(
              name,
              signatureOf(line),
              first + 1,
              last + 1,
              span.isPresent(),
              String.join("\n", lines.subList(first, last + 1))));
    }
    return out;
  }

  /**
   * True when {@code name} is declared in the text as a method of a brace language, or as a
   * function of another language of the editor ({@code def}, {@code func}, {@code function}, {@code
   * fn}); for the latter {@link #find} has nothing to return and a step uses the whole file.
   */
  public static boolean declares(String text, String name) {
    if (!find(text, name).isEmpty()) return true;
    Matcher m = OTHER_FUNCTION.matcher(text);
    while (m.find()) if (m.group(1).equals(name)) return true;
    return false;
  }

  /**
   * The declarations at the level of a type's members: types and methods, one line each (the
   * declaration line exactly as written, only trimmed, so it can be copied as an anchor), in file
   * order. A method's body is not searched, so a local call or an anonymous class inside it is not
   * listed.
   */
  public static List<String> signatures(String text) {
    List<String> lines = text.lines().toList();
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < lines.size()) {
      String line = lines.get(i);
      Optional<String> declared = declaredName(line);
      if (declared.isEmpty()) {
        i++;
        continue;
      }
      out.add(line.strip());
      if (CodeSpans.isTypeDeclaration(line)) {
        i++; // the members of a type are listed too
        continue;
      }
      Optional<CodeSpans.Span> span = CodeSpans.declarationSpan(lines, i);
      i = span.map(s -> s.last() + 1).orElse(i + 1);
    }
    return out;
  }

  /**
   * The name a line declares. {@link CodeSpans#declaredName} leaves out lines that end with a
   * semicolon, which keeps calls out; a method with no body ends with one, and is a declaration
   * when what is before the semicolon reads as one ({@code abstract int f(int x);}).
   */
  private static Optional<String> declaredName(String line) {
    Optional<String> name = CodeSpans.declaredName(line);
    if (name.isPresent()) return name;
    String t = line.strip();
    if (!t.endsWith(";") || t.indexOf('(') < 0) return Optional.empty();
    return CodeSpans.declaredName(t.substring(0, t.length() - 1).stripTrailing() + " {");
  }

  /** The declaration line as one comparable, readable line: trimmed, no opening brace. */
  private static String signatureOf(String line) {
    String t = line.strip();
    if (t.endsWith("{")) t = t.substring(0, t.length() - 1).stripTrailing();
    return t;
  }
}
