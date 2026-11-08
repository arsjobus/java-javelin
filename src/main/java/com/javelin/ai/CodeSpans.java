package com.javelin.ai;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where a declaration starts and ends in brace languages (Java, JavaScript, C and the like),
 * by counting braces and skipping comments, strings, characters and text blocks. It is not a
 * parser: it answers "which lines does the method that starts here cover", and answers nothing when
 * it cannot tell. JavaFX-free.
 */
final class CodeSpans {
  private CodeSpans() {}

  /** The lines {@code first} to {@code last}, both included. */
  record Span(int first, int last) {
    boolean contains(int from, int length) {
      return from >= first && from + length - 1 <= last;
    }
  }

  private static final String MODIFIERS =
      "(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|sealed|non-sealed|async|export)\\s+)*";

  private static final Pattern TYPE_DECLARATION =
      Pattern.compile(
          "^" + MODIFIERS + "(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)\\b.*");

  private static final Pattern FUNCTION_DECLARATION =
      Pattern.compile("^" + MODIFIERS + "function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\(.*");

  /** modifiers, optional type parameters, a return type, a name and an opening parenthesis. */
  private static final Pattern METHOD_DECLARATION =
      Pattern.compile(
          "^"
              + MODIFIERS
              + "(?:<[^>]+>\\s+)?[A-Za-z_$][\\w$.<>\\[\\],? ]*?\\s+([A-Za-z_$][\\w$]*)\\s*\\(.*");

  private static final Pattern CONSTRUCTOR_DECLARATION =
      Pattern.compile("^" + MODIFIERS + "([A-Z][\\w$]*)\\s*\\(.*");

  private static final Pattern ANNOTATION_ONLY =
      Pattern.compile("^@[A-Za-z_$][\\w$.]*\\s*(\\(.*\\))?\\s*$");

  private static final List<String> NOT_DECLARATIONS =
      List.of(
          "if",
          "for",
          "while",
          "switch",
          "return",
          "new",
          "else",
          "catch",
          "try",
          "throw",
          "do",
          "synchronized",
          "assert",
          "case",
          "yield",
          "super",
          "this");

  /** The name a declaration line declares, if the line is the start of a type or a method. */
  static Optional<String> declaredName(String line) {
    String t = LineMatch.squeeze(line);
    if (t.isEmpty()
        || t.startsWith("@")
        || t.startsWith("//")
        || t.startsWith("*")
        || t.endsWith(";")) return Optional.empty();
    Matcher type = TYPE_DECLARATION.matcher(t);
    if (type.matches()) return Optional.of(type.group(1));
    Matcher function = FUNCTION_DECLARATION.matcher(t);
    if (function.matches()) return Optional.of(function.group(1));
    String firstWord = t.split("[\\s(]", 2)[0];
    if (NOT_DECLARATIONS.contains(firstWord)) return Optional.empty();
    if (t.contains("=") && t.indexOf('=') < t.indexOf('(')) return Optional.empty();
    Matcher method = METHOD_DECLARATION.matcher(t);
    if (method.matches() && !NOT_DECLARATIONS.contains(method.group(1)))
      return Optional.of(method.group(1));
    Matcher constructor = CONSTRUCTOR_DECLARATION.matcher(t);
    if (constructor.matches()) return Optional.of(constructor.group(1));
    return Optional.empty();
  }

  /** True for a line that holds only an annotation ({@code @Override}, {@code @Test(x = 1)}). */
  static boolean isAnnotationOnly(String line) {
    return ANNOTATION_ONLY.matcher(line.strip()).matches();
  }

  /**
   * A declaration line made comparable: spaces squeezed and a trailing opening brace removed, so
   * {@code public int f(int a) {} } and {@code public int f(int a)} are the same signature.
   */
  static String signatureKey(String line) {
    String t = LineMatch.squeeze(line);
    if (t.endsWith("{")) t = t.substring(0, t.length() - 1).strip();
    return t;
  }

  /**
   * The lines a declaration that starts on line {@code declaration} covers, from that line to the
   * line with its closing brace. Empty when the declaration has no body (it ends with {@code ;}
   * first), when no brace opens within a few lines, or when the braces do not balance.
   */
  static Optional<Span> declarationSpan(List<String> lines, int declaration) {
    int depth = 0;
    boolean opened = false;
    boolean inBlockComment = false;
    boolean inTextBlock = false;
    for (int i = declaration; i < lines.size(); i++) {
      if (!opened && i - declaration > 12) return Optional.empty();
      String line = lines.get(i);
      boolean inString = false;
      boolean inChar = false;
      for (int k = 0; k < line.length(); k++) {
        char c = line.charAt(k);
        char next = k + 1 < line.length() ? line.charAt(k + 1) : '\0';
        if (inBlockComment) {
          if (c == '*' && next == '/') {
            inBlockComment = false;
            k++;
          }
          continue;
        }
        if (inTextBlock) {
          if (c == '\\') k++;
          else if (line.startsWith("\"\"\"", k)) {
            inTextBlock = false;
            k += 2;
          }
          continue;
        }
        if (inString || inChar) {
          if (c == '\\') k++;
          else if (inString && c == '"') inString = false;
          else if (inChar && c == '\'') inChar = false;
          continue;
        }
        if (c == '/' && next == '/') break;
        if (c == '/' && next == '*') {
          inBlockComment = true;
          k++;
        } else if (line.startsWith("\"\"\"", k)) {
          inTextBlock = true;
          k += 2;
        } else if (c == '"') inString = true;
        else if (c == '\'') inChar = true;
        else if (c == ';' && !opened && depth == 0) return Optional.empty();
        else if (c == '{') {
          opened = true;
          depth++;
        } else if (c == '}') {
          depth--;
          if (opened && depth == 0) return Optional.of(new Span(declaration, i));
          if (depth < 0) return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }
}
