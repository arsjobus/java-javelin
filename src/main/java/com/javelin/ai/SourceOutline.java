package com.javelin.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The members of a Java source file with the lines each one covers: the types, their methods and
 * constructors, their fields (also when the initializer runs over several lines), initializer
 * blocks and an enum's constants. It is {@link CodeSpans} and {@link MethodFinder} taken one step
 * further, for the symbol index of Phase 4 of ROADMAP-AI-RELIABILITY.md: declarations are found by
 * pattern and their bodies by counting braces, so it is right for ordinary Java and says so when it
 * is not. It is not a parser. A line at the level of a type's members that it cannot place is
 * counted in {@link Outline#unrecognisedLines()}, and a caller that must not lose any code (the
 * skeleton view) then falls back to the whole file. JavaFX-free.
 */
public final class SourceOutline {
  private SourceOutline() {}

  public enum Kind {
    TYPE,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    /** A {@code static { }} or instance initializer block. */
    INITIALIZER
  }

  /**
   * One member. {@code first}, {@code last} and {@code open} are 1-based line numbers: {@code
   * first} includes the annotations written above the declaration, {@code last} is the line with
   * the closing brace (the declaration line itself for a field or a method with no body), and
   * {@code open} is the line that holds the opening brace of a body (the declaration line when
   * there is none). {@code owner} is the enclosing types, outermost first, joined with dots (empty
   * at the top level). {@code signature} is the declaration on one line without its brace.
   */
  public record Member(
      Kind kind,
      String name,
      String owner,
      String signature,
      int first,
      int last,
      int open,
      boolean hasBody) {
    /** {@code Owner.name}, or just the name at the top level. */
    public String qualified() {
      return owner.isEmpty() ? name : owner + "." + name;
    }

    public boolean isCallable() {
      return kind == Kind.METHOD || kind == Kind.CONSTRUCTOR;
    }
  }

  /**
   * The members in file order, how many lines at member level could not be placed, and the first of
   * them (1-based; 0 when there is none).
   */
  public record Outline(List<Member> members, int unrecognisedLines, int firstUnrecognised) {
    public Outline {
      members = List.copyOf(members);
    }

    /** True when every line at member level was a member, a comment or an annotation. */
    public boolean isComplete() {
      return unrecognisedLines == 0;
    }
  }

  private static final String MODIFIERS =
      "(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*";

  private static final Pattern FIELD =
      Pattern.compile(
          "^"
              + MODIFIERS
              + "[A-Za-z_$][\\w$.<>\\[\\],? ]*?\\s+([A-Za-z_$][\\w$]*)\\s*(?:=.*)?;\\s*(?://.*)?$");

  /** The first line of a field whose initializer goes on: it has an {@code =} and no semicolon. */
  private static final Pattern FIELD_START =
      Pattern.compile(
          "^" + MODIFIERS + "[A-Za-z_$][\\w$.<>\\[\\],? ]*?\\s+([A-Za-z_$][\\w$]*)\\s*=[^;]*$");

  /** A record's compact canonical constructor: its name and a brace, no parameter list. */
  private static final Pattern COMPACT_CONSTRUCTOR =
      Pattern.compile("^" + MODIFIERS + "([A-Z][\\w$]*)\\s*\\{.*");

  private static final Pattern INITIALIZER = Pattern.compile("^(static\\s*)?\\{.*");
  private static final Pattern ENUM_CONSTANT =
      Pattern.compile("^[A-Z][A-Z0-9_]*\\b\\s*(?:[(,;{]|$).*");
  private static final int MAX_STATEMENT_LINES = 200;

  private record Open(String name, String path, int last) {}

  /** Reads the outline of {@code text}. Text that is not Java gives few or no members. */
  public static Outline of(String text) {
    return new Reader(text.lines().toList()).read();
  }

  private static final class Reader {
    final List<String> lines;
    final List<Member> members = new ArrayList<>();
    final Deque<Open> types = new ArrayDeque<>();
    int unrecognised;
    int firstUnrecognised;
    int pending = -1; // the first line of the annotations waiting for their declaration

    Reader(List<String> lines) {
      this.lines = lines;
    }

    Outline read() {
      int i = 0;
      while (i < lines.size()) {
        while (!types.isEmpty() && i > types.peek().last()) types.pop();
        i = step(i);
      }
      return new Outline(members, unrecognised, firstUnrecognised);
    }

    /** Handles the line at {@code i} and returns the index of the next line to look at. */
    private int step(int i) {
      String line = lines.get(i);
      String t = line.strip();
      if (t.isEmpty() || isComment(t)) return i + 1;
      if (t.startsWith("package ") || t.startsWith("import ") || t.startsWith("}")) return i + 1;
      if (t.startsWith("@") && !t.startsWith("@interface")) {
        String rest = afterAnnotations(t);
        if (pending < 0) pending = i;
        if (rest.isEmpty()) return endOfAnnotation(i) + 1; // annotations on lines of their own
        return declaration(i, rest);
      }
      return declaration(i, line);
    }

    private int declaration(int i, String text) {
      String owner = types.isEmpty() ? "" : types.peek().path();
      int first = pending >= 0 ? pending : i;
      pending = -1;
      Optional<String> name = MethodFinder.declaredName(text);
      if (name.isPresent()) {
        Optional<CodeSpans.Span> span = CodeSpans.declarationSpan(lines, i);
        boolean hasBody = span.isPresent();
        int last = span.map(CodeSpans.Span::last).orElse(i);
        int open = hasBody ? openLine(i, last) : i;
        String signature = signature(i, open, hasBody, text);
        if (CodeSpans.isTypeDeclaration(text)) {
          members.add(
              new Member(
                  Kind.TYPE, name.get(), owner, signature, first + 1, last + 1, open + 1, hasBody));
          String path = owner.isEmpty() ? name.get() : owner + "." + name.get();
          if (!hasBody) unrecognise(i); // a type whose braces do not balance cannot be trusted
          types.push(new Open(name.get(), path, hasBody ? last : i));
          boolean isEnum = signature.matches("(?:.*\\s)?enum\\s+.*");
          int next = open + 1;
          return isEnum && hasBody && last > open ? constants(next, last) : next;
        }
        boolean constructor = !types.isEmpty() && name.get().equals(types.peek().name());
        members.add(
            new Member(
                constructor ? Kind.CONSTRUCTOR : Kind.METHOD,
                name.get(),
                owner,
                signature,
                first + 1,
                last + 1,
                open + 1,
                hasBody));
        return last + 1;
      }
      if (types.isEmpty()) return i + 1;
      String squeezed = LineMatch.squeeze(text);
      Matcher field = FIELD.matcher(squeezed);
      if (field.matches() && !startsLikeStatement(squeezed)) {
        members.add(field(firstDeclarator(squeezed, field), owner, squeezed, first, i, i));
        return i + 1;
      }
      Matcher start = FIELD_START.matcher(squeezed);
      if (start.matches() && !startsLikeStatement(squeezed)) {
        int end = statementEnd(i);
        if (end >= 0) {
          members.add(field(start.group(1), owner, squeezed, first, i, end));
          return end + 1;
        }
      }
      Matcher compact = COMPACT_CONSTRUCTOR.matcher(squeezed);
      if (compact.matches() && compact.group(1).equals(types.peek().name())) {
        Optional<CodeSpans.Span> span = CodeSpans.declarationSpan(lines, i);
        if (span.isPresent()) {
          int open = openLine(i, span.get().last());
          members.add(
              new Member(
                  Kind.CONSTRUCTOR,
                  compact.group(1),
                  owner,
                  signature(i, open, true, text),
                  first + 1,
                  span.get().last() + 1,
                  open + 1,
                  true));
          return span.get().last() + 1;
        }
      }
      if (INITIALIZER.matcher(squeezed).matches()) {
        Optional<CodeSpans.Span> span = CodeSpans.declarationSpan(lines, i);
        if (span.isPresent()) {
          boolean isStatic = squeezed.startsWith("static");
          members.add(
              new Member(
                  Kind.INITIALIZER,
                  isStatic ? "<static>" : "<initializer>",
                  owner,
                  isStatic ? "static" : "",
                  first + 1,
                  span.get().last() + 1,
                  openLine(i, span.get().last()) + 1,
                  true));
          return span.get().last() + 1;
        }
      }
      unrecognise(i);
      return i + 1;
    }

    /**
     * The constants of an enum: the lines from {@code from} up to the first semicolon at the top
     * level of the body, or up to the closing brace when there is none. They are one member, shown
     * as written. Returns the next line to look at.
     */
    private int constants(int from, int closing) {
      int i = from;
      while (i < closing && (lines.get(i).isBlank() || isComment(lines.get(i).strip()))) i++;
      if (i >= closing) return from;
      String t = lines.get(i).strip();
      boolean semicolonOnly = t.startsWith(";");
      if (!semicolonOnly && !ENUM_CONSTANT.matcher(t).matches()) return from;
      int end = semicolonOnly ? i : statementEnd(i);
      if (end < 0 || end >= closing) end = closing - 1; // constants and nothing else
      if (!semicolonOnly)
        members.add(
            new Member(
                Kind.FIELD,
                "<constants>",
                types.peek().path(),
                t.endsWith(",") || t.endsWith(";") ? t.substring(0, t.length() - 1) : t,
                i + 1,
                end + 1,
                end + 1,
                false));
      return end + 1;
    }

    private Member field(String name, String owner, String squeezed, int first, int from, int to) {
      String signature =
          squeezed.endsWith(";") ? squeezed.substring(0, squeezed.length() - 1) : squeezed;
      return new Member(
          Kind.FIELD, name, owner, signature.strip(), first + 1, to + 1, from + 1, false);
    }

    private void unrecognise(int i) {
      unrecognised++;
      if (firstUnrecognised == 0) firstUnrecognised = i + 1;
    }

    /** The line where the annotation that starts at {@code i} ends (its parentheses balance). */
    private int endOfAnnotation(int i) {
      int depth = 0;
      for (int n = i; n < lines.size() && n < i + MAX_STATEMENT_LINES; n++) {
        depth += parenDelta(lines.get(n));
        if (depth <= 0) return n;
      }
      return i;
    }

    /** The line holding the first {@code ;} that is not inside brackets, or -1. */
    private int statementEnd(int from) {
      Scan scan = new Scan();
      for (int n = from; n < lines.size() && n < from + MAX_STATEMENT_LINES; n++) {
        int result = scan.line(lines.get(n));
        if (result == Scan.ENDED) return n;
        if (result == Scan.BROKEN) return -1;
      }
      return -1;
    }

    /** The first line from {@code declaration} to {@code last} that holds an opening brace. */
    private int openLine(int declaration, int last) {
      for (int i = declaration; i <= last; i++) if (lines.get(i).indexOf('{') >= 0) return i;
      return declaration;
    }

    private String signature(int declaration, int open, boolean hasBody, String firstText) {
      StringBuilder out = new StringBuilder(firstText.strip());
      for (int i = declaration + 1; i <= open; i++) out.append(' ').append(lines.get(i).strip());
      String s = out.toString();
      int brace = s.indexOf('{');
      if (brace >= 0) s = s.substring(0, brace);
      if (!hasBody && s.endsWith(";")) s = s.substring(0, s.length() - 1);
      return s.strip();
    }
  }

  /** Reads text for the end of a statement: a semicolon outside strings, comments and brackets. */
  private static final class Scan {
    static final int OPEN = 0;
    static final int ENDED = 1;
    static final int BROKEN = 2;
    int depth;
    boolean inBlockComment;
    boolean inTextBlock;

    int line(String line) {
      for (int k = 0; k < line.length(); k++) {
        char c = line.charAt(k);
        char next = k + 1 < line.length() ? line.charAt(k + 1) : '\0';
        if (inBlockComment) {
          if (c == '*' && next == '/') {
            inBlockComment = false;
            k++;
          }
        } else if (inTextBlock) {
          if (c == '\\') k++;
          else if (line.startsWith("\"\"\"", k)) {
            inTextBlock = false;
            k += 2;
          }
        } else if (c == '/' && next == '/') {
          return OPEN;
        } else if (c == '/' && next == '*') {
          inBlockComment = true;
          k++;
        } else if (line.startsWith("\"\"\"", k)) {
          inTextBlock = true;
          k += 2;
        } else if (c == '"' || c == '\'') {
          k = skipQuoted(line, k, c);
        } else if (c == '(' || c == '[' || c == '{') {
          depth++;
        } else if (c == ')' || c == ']' || c == '}') {
          if (--depth < 0) return BROKEN;
        } else if (c == ';' && depth == 0) {
          return ENDED;
        }
      }
      return OPEN;
    }

    private static int skipQuoted(String line, int start, char quote) {
      int k = start + 1;
      while (k < line.length()) {
        char c = line.charAt(k);
        if (c == '\\') k++;
        else if (c == quote) return k;
        k++;
      }
      return line.length();
    }
  }

  /** What is left of {@code t} once the annotations at its start are removed ("" if only they). */
  private static String afterAnnotations(String t) {
    String rest = t;
    while (rest.startsWith("@") && !rest.startsWith("@interface")) {
      int i = 1;
      while (i < rest.length()
          && (Character.isJavaIdentifierPart(rest.charAt(i)) || rest.charAt(i) == '.')) i++;
      if (i < rest.length() && rest.charAt(i) == '(') {
        int depth = 0;
        int k = i;
        for (; k < rest.length(); k++) {
          char c = rest.charAt(k);
          if (c == '(') depth++;
          else if (c == ')' && --depth == 0) break;
        }
        if (k >= rest.length()) return ""; // the parentheses go on over the next lines
        i = k + 1;
      }
      rest = rest.substring(i).strip();
    }
    return rest;
  }

  private static int parenDelta(String line) {
    int d = 0;
    boolean inString = false;
    for (int k = 0; k < line.length(); k++) {
      char c = line.charAt(k);
      if (c == '\\') k++;
      else if (c == '"') inString = !inString;
      else if (!inString && c == '(') d++;
      else if (!inString && c == ')') d--;
    }
    return d;
  }

  private static boolean isComment(String t) {
    return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*");
  }

  /**
   * The name of the field, or of the first one when a line declares several ({@code Path a, b;}:
   * the pattern's own name group is the last).
   */
  private static String firstDeclarator(String squeezed, Matcher field) {
    int equals = squeezed.indexOf('=');
    String declared = squeezed.substring(0, equals < 0 ? squeezed.length() - 1 : equals);
    int comma = declared.indexOf(',');
    if (equals >= 0 || comma < 0 || declared.contains("<")) return field.group(1);
    String firstPart = declared.substring(0, comma).strip();
    int space = firstPart.lastIndexOf(' ');
    return space < 0 ? field.group(1) : firstPart.substring(space + 1);
  }

  private static boolean startsLikeStatement(String t) {
    String first = t.split("[\\s(]", 2)[0];
    return List.of("return", "throw", "break", "continue", "case", "default", "yield", "assert")
        .contains(first);
  }
}
