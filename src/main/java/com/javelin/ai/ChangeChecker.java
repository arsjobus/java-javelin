package com.javelin.ai;

import com.javelin.editor.SyntaxHighlighter;
import com.javelin.editor.SyntaxHighlighter.Language;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Looks at a proposed change before the user does and says what is wrong with it. A model's reply
 * cannot be trusted to be code that fits: it may stand a placeholder where code was, lose a closing
 * brace, drop half the lines or leave our own markers in. None of these checks needs the rest of
 * the project, and none of them decides anything: the findings are shown beside the diff, and an
 * error makes Apply say "Apply anyway". JavaFX-free.
 */
public final class ChangeChecker {
  private ChangeChecker() {}

  /**
   * Findings for turning {@code original} (the text that was sent) into {@code replacement}; errors
   * first.
   */
  public static List<Finding> check(String languageId, String original, String replacement) {
    String before = original.replace("\r\n", "\n"), after = replacement.replace("\r\n", "\n");
    List<Finding> found = new ArrayList<>();
    if (before.equals(after)) {
      found.add(Finding.warning("The proposed text is the same as the current text."));
      return found;
    }
    placeholders(languageId, before, after, found);
    scaffolding(before, after, found);
    brackets(languageId, before, after, found);
    javaSyntax(languageId, before, after, found);
    removedDeclarations(languageId, before, after, found);
    shrinkage(before, after, found);
    found.sort((a, b) -> Boolean.compare(b.isError(), a.isError()));
    return found;
  }

  // ---- placeholders: "// ... rest of the method" where code should be ----

  private static final Pattern LEADER_DOTS =
      Pattern.compile("^(//+|#+|/\\*+|\\*+|--|<!--|;+)\\s*(\\.{3}|\\u2026)(.*)$");
  private static final Pattern BARE_DOTS = Pattern.compile("^(\\.{3}|\\u2026)\\s*$");
  private static final Pattern LEFT_OUT =
      Pattern.compile(
          "^(//+|#+|/\\*+|\\*+|--|<!--|;+)\\s*.*\\b(rest|remaining|existing|previous|unchanged|other)\\b.{0,24}\\b(code|methods?|implementation|logic|lines|content|cases|functions?|fields?)\\b.*$",
          Pattern.CASE_INSENSITIVE);

  private static void placeholders(
      String languageId, String before, String after, List<Finding> out) {
    Set<String> known = new HashSet<>();
    for (String line : before.split("\n", -1)) known.add(line.strip());
    List<String> lines = List.of(after.split("\n", -1));
    int reported = 0;
    for (int i = 0; i < lines.size() && reported < 3; i++) {
      String t = lines.get(i).strip();
      if (t.isEmpty() || known.contains(t)) continue;
      boolean bareOk = !"python".equals(languageId); // a lone ... is a real statement in Python
      if (LEADER_DOTS.matcher(t).matches()
          || (bareOk && BARE_DOTS.matcher(t).matches())
          || LEFT_OUT.matcher(t).matches()) {
        out.add(
            Finding.error(
                "Line "
                    + (i + 1)
                    + " of the new text is a placeholder (`"
                    + (t.length() > 50 ? t.substring(0, 47) + "..." : t)
                    + "`), not code. Applying it would delete the code it stands for."));
        reported++;
      }
    }
  }

  // ---- our own markers and line numbers that leaked into the code ----

  private static final Pattern MARKERS =
      Pattern.compile(
          "(={3,}\\s*(BEGIN|END) CODE)|(^\\s*<{7}(\\s|$))|(^\\s*>{7}(\\s|$))", Pattern.MULTILINE);
  private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d{1,5}\\s*[:|]\\s.*$");

  private static void scaffolding(String before, String after, List<Finding> out) {
    Matcher m = MARKERS.matcher(after);
    if (m.find() && !MARKERS.matcher(before).find())
      out.add(
          Finding.error(
              "The new text contains a marker line (`"
                  + m.group().strip()
                  + "`) from the request or from an edit block. It is not part of the code."));
    if (numberedShare(after) >= 0.6 && numberedShare(before) < 0.3)
      out.add(
          Finding.error(
              "Most lines of the new text start with a line number. The model added them; they are not part of the code."));
  }

  private static double numberedShare(String text) {
    int total = 0, numbered = 0;
    for (String line : text.split("\n", -1)) {
      if (line.isBlank()) continue;
      total++;
      if (NUMBERED.matcher(line).matches()) numbered++;
    }
    return total < 3 ? 0 : (double) numbered / total;
  }

  // ---- brackets must balance as they did ----

  private static void brackets(String languageId, String before, String after, List<Finding> out) {
    Language language = languageFor(languageId);
    if (language == null) return;
    int[] a = net(before, language), b = net(after, language);
    String kinds = "(){}[]";
    for (int k = 0; k < 3; k++) {
      if (a[k] == b[k]) continue;
      char open = kinds.charAt(2 * k), close = kinds.charAt(2 * k + 1);
      out.add(
          Finding.error(
              "Brackets no longer balance: `"
                  + open
                  + "` and `"
                  + close
                  + "` differ by "
                  + a[k]
                  + " in the selected code and by "
                  + b[k]
                  + " in the new text (opening minus closing, not counting strings and comments). A bracket was probably lost or added."));
    }
  }

  /** Opening minus closing brackets for (), {} and [], outside strings and comments. */
  private static int[] net(String text, Language language) {
    int[] net = new int[3];
    List<SyntaxHighlighter.Span> spans = SyntaxHighlighter.spansIn(text, language);
    int s = 0;
    for (int i = 0; i < text.length(); i++) {
      while (s < spans.size() && spans.get(s).end() <= i) s++;
      if (s < spans.size()
          && spans.get(s).start() <= i
          && ("string".equals(spans.get(s).style()) || "comment".equals(spans.get(s).style())))
        continue;
      switch (text.charAt(i)) {
        case '(' -> net[0]++;
        case ')' -> net[0]--;
        case '{' -> net[1]++;
        case '}' -> net[1]--;
        case '[' -> net[2]++;
        case ']' -> net[2]--;
        default -> {}
      }
    }
    return net;
  }

  /**
   * The bracket-checkable languages; markup, Markdown, YAML and shell are left out because their
   * brackets are not paired code.
   */
  static Language languageFor(String id) {
    if (id == null) return null;
    return switch (id) {
      case "java" -> Language.JAVA;
      case "kotlin" -> Language.KOTLIN;
      case "javascript", "javascriptreact" -> Language.JAVASCRIPT;
      case "typescript", "typescriptreact" -> Language.TYPESCRIPT;
      case "c" -> Language.C;
      case "cpp" -> Language.CPP;
      case "go" -> Language.GO;
      case "rust" -> Language.RUST;
      case "php" -> Language.PHP;
      case "json" -> Language.JSON;
      case "python" -> Language.PYTHON;
      default -> null;
    };
  }

  // ---- Java: does it still parse? ----

  private static final Pattern SYNTAX_CODE =
      Pattern.compile(
          "compiler\\.err\\.(expected\\d?|illegal\\..*|premature\\.eof|not\\.stmt|orphaned|.*\\.without\\..*|unclosed\\..*"
              + "|invalid\\.meth\\.decl\\.ret\\.type\\.req|variable\\.not\\.allowed|array\\.dimension\\.missing|empty\\.char\\.lit|malformed\\..*|else\\.without\\.if)");

  /**
   * How a piece of Java is wrapped so that it parses on its own: as a file, as members of a class,
   * or as statements of a method.
   */
  private record Wrapper(String head, String tail, int headLines) {}

  private static final List<Wrapper> WRAPPERS =
      List.of(
          new Wrapper("", "", 0),
          new Wrapper("class W__ {\n", "\n}\n", 1),
          new Wrapper("class W__ { void m__() {\n", "\n} }\n", 1));

  private static void javaSyntax(
      String languageId, String before, String after, List<Finding> out) {
    if (!"java".equals(languageId)) return;
    JavaCompiler compiler;
    try {
      compiler = ToolProvider.getSystemJavaCompiler();
    } catch (RuntimeException | LinkageError ex) {
      return;
    }
    if (compiler == null) return; // a runtime without the compiler: the other checks still ran
    for (Wrapper wrapper : WRAPPERS) {
      List<String> original = syntaxErrors(compiler, wrapper, before);
      if (original == null || !original.isEmpty())
        continue; // the selected code does not parse in this form: try the next
      List<String> errors = syntaxErrors(compiler, wrapper, after);
      if (errors != null)
        for (String error : errors.subList(0, Math.min(3, errors.size())))
          out.add(Finding.error("Java syntax: " + error));
      return;
    }
    // the selection is not a unit that parses on its own (a half statement, say): only the bracket
    // check applies
  }

  /** Syntax errors as "line N: message", or null when the compiler could not be run. */
  private static List<String> syntaxErrors(JavaCompiler compiler, Wrapper wrapper, String code) {
    String source = wrapper.head() + code + wrapper.tail();
    JavaFileObject file =
        new SimpleJavaFileObject(URI.create("string:///W__.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try {
      compiler
          .getTask(
              null,
              null,
              diagnostics,
              List.of(
                  "-proc:none",
                  "-Xlint:none",
                  "-XDshould-stop.ifError=PARSE",
                  "-XDshould-stop.ifNoError=PARSE"),
              null,
              List.of(file))
          .call();
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
    List<String> errors = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      if (d.getKind() != Diagnostic.Kind.ERROR
          || d.getCode() == null
          || !SYNTAX_CODE.matcher(d.getCode()).matches()) continue;
      long line = Math.max(1, d.getLineNumber() - wrapper.headLines());
      errors.add("line " + line + ": " + d.getMessage(Locale.ROOT).lines().findFirst().orElse(""));
    }
    return errors;
  }

  // ---- declarations that disappeared ----

  private static final Pattern JAVA_METHOD =
      Pattern.compile(
          "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)+[\\w<>\\[\\],.?& ]+\\s+(\\w+)\\s*\\(",
          Pattern.MULTILINE);

  private static void removedDeclarations(
      String languageId, String before, String after, List<Finding> out) {
    if (!"java".equals(languageId)) return;
    Set<String> gone = new LinkedHashSet<>();
    Matcher m = JAVA_METHOD.matcher(before);
    while (m.find()) {
      String name = m.group(1);
      if (!Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(after).find())
        gone.add(name + "()");
    }
    if (!gone.isEmpty())
      out.add(
          Finding.warning(
              "No longer mentioned anywhere in the new text: "
                  + String.join(", ", gone)
                  + ". A refactor should keep a method unless you asked to remove it."));
  }

  // ---- much shorter than what was sent ----

  private static void shrinkage(String before, String after, List<Finding> out) {
    int a = countLines(before), b = countLines(after);
    if (a >= 12 && b * 2 < a)
      out.add(
          Finding.warning(
              "The new text is much shorter than the selected code ("
                  + a
                  + " lines became "
                  + b
                  + "). Check that nothing was dropped."));
  }

  private static int countLines(String text) {
    int n = 0;
    for (String line : text.split("\n", -1)) if (!line.isBlank()) n++;
    return n;
  }
}
