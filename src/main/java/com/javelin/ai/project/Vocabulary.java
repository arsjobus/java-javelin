package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a request, a path or a piece of code into lower-case words, and says which words belong
 * together. Everything is plain string work with a short built-in list of related words: it is
 * deterministic, needs no model and can be read in a minute. When a request misses a file it should
 * have found, the fix is usually one more line in {@link #RELATED}; the evaluation fixtures ({@code
 * SelectionEvaluation}) are where such a miss is recorded first.
 */
public final class Vocabulary {
  private Vocabulary() {}

  /**
   * A word of the request and how much it counts: 1.0 for the user's own, less for related ones.
   */
  public record Term(String word, double weight) {}

  private static final Set<String> STOP_WORDS =
      Set.of(
          "a",
          "an",
          "the",
          "and",
          "or",
          "of",
          "to",
          "in",
          "on",
          "for",
          "with",
          "from",
          "into",
          "that",
          "this",
          "it",
          "its",
          "is",
          "are",
          "be",
          "as",
          "at",
          "by",
          "add",
          "adds",
          "adding",
          "new",
          "make",
          "create",
          "build",
          "implement",
          "support",
          "feature",
          "please",
          "want",
          "need",
          "should",
          "would",
          "could",
          "can",
          "ad",
          "hoc",
          "some",
          "all",
          "any",
          "also",
          "using",
          "use",
          "so",
          "we",
          "our",
          "my",
          "me",
          "i",
          "you",
          "your",
          "when",
          "then",
          "than",
          "like",
          "get",
          "set",
          "do",
          "does",
          "not",
          "no",
          "yes",
          "only",
          "just",
          "more",
          "most",
          "each",
          "include",
          "including",
          "code",
          "project",
          "app",
          "application",
          "ability");

  /** Words that tend to appear together in code about the same thing. */
  static final Map<String, List<String>> RELATED = new LinkedHashMap<>();

  static {
    RELATED.put("uml", List.of("diagram", "class", "graph", "model"));
    RELATED.put("diagram", List.of("graph", "visual", "chart", "render"));
    RELATED.put("generator", List.of("generate", "builder", "render", "emit"));
    RELATED.put("parse", List.of("parser", "syntax", "token", "lexer", "ast"));
    RELATED.put("parser", List.of("parse", "syntax", "token", "lexer", "ast"));
    RELATED.put("test", List.of("junit", "assert"));
    RELATED.put("editor", List.of("codearea", "document", "buffer", "tab"));
    RELATED.put("ai", List.of("ollama", "prompt", "llm", "provider"));
    RELATED.put("ui", List.of("controller", "pane", "dialog", "view", "javafx", "menu"));
    RELATED.put("setting", List.of("config", "preference", "option"));
    RELATED.put("menu", List.of("action", "shortcut"));
    RELATED.put("search", List.of("find", "match"));
    RELATED.put("format", List.of("indent", "style"));
    RELATED.put("run", List.of("process", "launch"));
    RELATED.put("export", List.of("save", "write", "serialize"));
    RELATED.put("import", List.of("load", "read", "open"));
    RELATED.put("theme", List.of("color", "style", "css"));
    RELATED.put("git", List.of("commit", "branch", "repository"));
    RELATED.put("terminal", List.of("shell", "process", "console"));
  }

  private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
  private static final Pattern CAMEL = Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+");

  /** The words of a string, split at case changes, digits and anything that is not a letter. */
  public static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    Matcher word = WORD.matcher(text);
    while (word.find()) {
      Matcher part = CAMEL.matcher(word.group());
      while (part.find()) {
        String w = part.group().toLowerCase(Locale.ROOT);
        if (w.length() >= 2) out.add(w);
      }
    }
    return out;
  }

  /** A word with its plural and verb endings removed, so "diagrams" and "diagram" meet. */
  public static String stem(String word) {
    String w = word;
    if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
    if (w.length() > 4 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches")))
      return w.substring(0, w.length() - 2);
    if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us"))
      return w.substring(0, w.length() - 1);
    if (w.length() > 6 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
    if (w.length() > 5 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
    return w;
  }

  /** The request as terms: its own words first, then related words at half weight. */
  public static List<Term> terms(String request) {
    Map<String, Term> terms = new LinkedHashMap<>();
    for (String w : words(request)) {
      if (STOP_WORDS.contains(w)) continue;
      String s = stem(w);
      if (!STOP_WORDS.contains(s)) terms.putIfAbsent(s, new Term(s, 1.0));
    }
    List<Term> own = new ArrayList<>(terms.values());
    for (Term t : own)
      for (String related : RELATED.getOrDefault(t.word(), List.of()))
        terms.putIfAbsent(stem(related), new Term(stem(related), 0.5));
    return new ArrayList<>(terms.values());
  }

  /**
   * True when two words are about the same thing: equal, one starts the other ("edit", "editor"),
   * or they share a long beginning ("generator", "generate").
   */
  public static boolean related(String a, String b) {
    if (a.equals(b)) return true;
    String shorter = a.length() <= b.length() ? a : b;
    String longer = a.length() <= b.length() ? b : a;
    if (shorter.length() >= 4 && longer.startsWith(shorter)) return true;
    int common = 0;
    while (common < shorter.length() && a.charAt(common) == b.charAt(common)) common++;
    return common >= 5 && common >= shorter.length() - 2;
  }
}
