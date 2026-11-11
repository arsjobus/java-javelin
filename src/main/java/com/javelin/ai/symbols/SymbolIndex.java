package com.javelin.ai.symbols;

import com.javelin.ai.SourceOutline;
import com.javelin.ai.SourceOutline.Kind;
import com.javelin.ai.SourceOutline.Member;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Java files of a project declare and where they call each other: types, method and
 * constructor signatures, fields, package and imports, and call sites. It answers the questions
 * Phase 4 of ROADMAP-AI-RELIABILITY.md asks to build a small context: which symbols does this
 * request mean, which project types does a method use, and who calls it directly.
 *
 * <p>It is built from the text of the files it is given (no disk, no clock, no randomness), by the
 * {@link OutlineSource} it is given, so the same input gives the same answers. Calls are found by
 * name: {@code add(} in a body is a call of every method called {@code add} in a file that could
 * see the type, which can be too many (overloads, unrelated types with the same method name) but
 * does not miss a call that is written out. It is a retrieval aid, not a type checker, and what it
 * reports is described that way in the preview. JavaFX-free.
 */
public final class SymbolIndex {
  /** A member of one file. */
  public record Symbol(String path, Member member) {
    public String name() {
      return member.name();
    }

    public Kind kind() {
      return member.kind();
    }

    /** {@code Owner.name}, as a person would say it. */
    public String qualified() {
      return member.qualified();
    }

    public boolean isCallable() {
      return member.isCallable();
    }
  }

  /** A call of a method or constructor: the symbol that makes it and the first line that does. */
  public record CallSite(Symbol caller, int line, String text) {}

  private static final Pattern TYPE_NAME = Pattern.compile("(?<![\\w$])[A-Z][A-Za-z0-9_$]*");
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

  private final Map<String, String> texts = new LinkedHashMap<>();
  private final Map<String, SourceOutline.Outline> outlines = new LinkedHashMap<>();
  private final Map<String, String> packages = new LinkedHashMap<>();
  private final Map<String, List<String>> imports = new LinkedHashMap<>();
  private final List<Symbol> symbols = new ArrayList<>();

  private SymbolIndex() {}

  /**
   * Indexes {@code javaTexts} (path to text, in the order the paths should be reported). A file the
   * source has no outline for is kept as text and has no symbols.
   */
  public static SymbolIndex build(Map<String, String> javaTexts, OutlineSource source) {
    SymbolIndex index = new SymbolIndex();
    for (Map.Entry<String, String> e : javaTexts.entrySet()) {
      String path = e.getKey();
      String text = e.getValue();
      index.texts.put(path, text);
      Matcher p = PACKAGE.matcher(text);
      index.packages.put(path, p.find() ? p.group(1) : "");
      List<String> imp = new ArrayList<>();
      Matcher i = IMPORT.matcher(text);
      while (i.find()) imp.add(i.group(1));
      index.imports.put(path, imp);
      source
          .outline(path, text)
          .ifPresent(
              outline -> {
                index.outlines.put(path, outline);
                for (Member m : outline.members()) index.symbols.add(new Symbol(path, m));
              });
    }
    return index;
  }

  public static SymbolIndex build(Map<String, String> javaTexts) {
    return build(javaTexts, OutlineSource.PATTERNS);
  }

  public List<String> paths() {
    return List.copyOf(texts.keySet());
  }

  public boolean contains(String path) {
    return texts.containsKey(path);
  }

  public String text(String path) {
    return texts.get(path);
  }

  /** The outline of a file, or null when the source had none. */
  public SourceOutline.Outline outline(String path) {
    return outlines.get(path);
  }

  public String packageOf(String path) {
    return packages.getOrDefault(path, "");
  }

  public List<String> importsOf(String path) {
    return imports.getOrDefault(path, List.of());
  }

  /** Every symbol, files in the order they were given and members in file order. */
  public List<Symbol> symbols() {
    return List.copyOf(symbols);
  }

  public List<Symbol> symbolsOf(String path) {
    List<Symbol> out = new ArrayList<>();
    for (Symbol s : symbols) if (s.path().equals(path)) out.add(s);
    return out;
  }

  /** The methods and constructors called {@code name}, in the order of {@link #symbols}. */
  public List<Symbol> callablesNamed(String name) {
    List<Symbol> out = new ArrayList<>();
    for (Symbol s : symbols) if (s.isCallable() && s.name().equals(name)) out.add(s);
    return out;
  }

  /**
   * The lines of the symbol, from its annotations to its closing brace, as they are in the file.
   */
  public String body(Symbol symbol) {
    List<String> lines = texts.get(symbol.path()).lines().toList();
    int from = Math.max(0, symbol.member().first() - 1);
    int to = Math.min(lines.size(), symbol.member().last());
    return String.join("\n", lines.subList(from, to));
  }

  /**
   * The types of other files of the index that the symbol's text names, in the order it names them
   * (the first type of a name when several files declare one, the one of the same package first).
   * Types of the symbol's own file are not listed: that file shows them anyway.
   */
  public List<Symbol> typesUsedBy(Symbol symbol) {
    Map<String, List<Symbol>> byName = new LinkedHashMap<>();
    for (Symbol s : symbols)
      if (s.kind() == Kind.TYPE && !s.path().equals(symbol.path()))
        byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
    List<Symbol> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher m = TYPE_NAME.matcher(withoutComments(body(symbol)));
    while (m.find()) {
      List<Symbol> candidates = byName.get(m.group());
      if (candidates == null || !seen.add(m.group())) continue;
      Symbol pick = candidates.get(0);
      for (Symbol c : candidates)
        if (packageOf(c.path()).equals(packageOf(symbol.path()))) {
          pick = c;
          break;
        }
      out.add(pick);
    }
    return out;
  }

  /**
   * The callers of a method or constructor in the files of the index: each method or constructor
   * whose body calls it by name, with its first call. For a method a call is {@code name(}; for a
   * constructor it is {@code new Type(}. A caller is listed only if its file could see the type: it
   * is the same file, the same package, or it names the owning type. Method bodies only: a call in
   * a field initializer is not found.
   */
  public List<CallSite> callSitesOf(Symbol target) {
    if (!target.isCallable()) return List.of();
    boolean constructor = target.kind() == Kind.CONSTRUCTOR;
    String ownerSimple = simpleName(target.member().owner());
    Pattern call =
        constructor
            ? Pattern.compile(
                "new\\s+(?:[\\w$]+\\s*\\.\\s*)*" + Pattern.quote(target.name()) + "\\s*[(<]")
            : Pattern.compile("(?<![\\w$])(?<!new\\s)" + Pattern.quote(target.name()) + "\\s*\\(");
    List<CallSite> out = new ArrayList<>();
    for (Symbol caller : symbols) {
      if (!caller.isCallable() || caller.equals(target) || !caller.member().hasBody()) continue;
      if (!couldSee(caller.path(), target.path(), ownerSimple)) continue;
      List<String> lines = texts.get(caller.path()).lines().toList();
      for (int n = caller.member().open(); n <= caller.member().last() && n <= lines.size(); n++) {
        String line = lines.get(n - 1);
        if (n == caller.member().open()) {
          int brace = line.indexOf('{');
          if (brace >= 0) line = line.substring(brace + 1); // not the caller's own declaration
        }
        String code = stripLineComment(line);
        if (call.matcher(code).find()) {
          out.add(new CallSite(caller, n, lines.get(n - 1).strip()));
          break;
        }
      }
    }
    return out;
  }

  private boolean couldSee(String callerPath, String targetPath, String ownerSimple) {
    if (callerPath.equals(targetPath)) return true;
    if (packageOf(callerPath).equals(packageOf(targetPath))) return true;
    if (ownerSimple.isEmpty()) return false;
    return Pattern.compile("(?<![\\w$])" + Pattern.quote(ownerSimple) + "(?![\\w$])")
        .matcher(texts.get(callerPath))
        .find();
  }

  private static String simpleName(String owner) {
    int dot = owner.lastIndexOf('.');
    return dot < 0 ? owner : owner.substring(dot + 1);
  }

  private static String stripLineComment(String line) {
    int slash = line.indexOf("//");
    return slash < 0 ? line : line.substring(0, slash);
  }

  private static String withoutComments(String text) {
    StringBuilder out = new StringBuilder();
    for (String line : text.lines().toList()) {
      String t = line.strip();
      if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) continue;
      out.append(stripLineComment(line)).append('\n');
    }
    return out.toString();
  }
}
