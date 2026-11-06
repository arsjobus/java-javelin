package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What can be read off a file with a few patterns and no parser: the types and functions it
 * declares, its package, what it imports and which capitalised names it mentions. Enough to follow
 * imports between files of the same project in the languages the editor knows; not a compiler.
 */
final class CodeFacts {
  final ProjectFile file;
  final List<String> declared = new ArrayList<>();
  final Map<String, Integer> wordFrequency = new HashMap<>();
  final Set<String> mentioned = new HashSet<>();
  final List<String> imports = new ArrayList<>();
  String packageName = "";

  private static final Pattern DECLARATION =
      Pattern.compile(
          "\\b(?:class|interface|enum|record|object|trait|struct|type)\\s+([A-Za-z_]\\w*)");
  private static final Pattern FUNCTION =
      Pattern.compile("\\b(?:def|func|function|fn)\\s+([A-Za-z_]\\w*)");
  private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final Pattern JAVA_IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");
  private static final Pattern JS_IMPORT =
      Pattern.compile("(?:from\\s+|require\\(\\s*|import\\s+)[\"'](\\.{1,2}/[^\"']+)[\"']");
  private static final Pattern PY_IMPORT =
      Pattern.compile("(?m)^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");
  private static final Pattern CAPITALISED = Pattern.compile("\\b[A-Z][A-Za-z0-9]{2,}\\b");

  CodeFacts(ProjectFile file, String text) {
    this.file = file;
    Matcher d = DECLARATION.matcher(text);
    while (d.find() && declared.size() < 200) declared.add(d.group(1));
    Matcher f = FUNCTION.matcher(text);
    while (f.find() && declared.size() < 200) declared.add(f.group(1));
    for (String w : Vocabulary.words(text))
      wordFrequency.merge(Vocabulary.stem(w), 1, Integer::sum);
    Matcher c = CAPITALISED.matcher(text);
    while (c.find()) mentioned.add(c.group());
    String language = file.language() == null ? "" : file.language();
    switch (language) {
      case "java", "kotlin", "scala" -> {
        Matcher p = JAVA_PACKAGE.matcher(text);
        if (p.find()) packageName = p.group(1);
        Matcher i = JAVA_IMPORT.matcher(text);
        while (i.find()) imports.add(i.group(1));
      }
      case "javascript", "javascriptreact", "typescript", "typescriptreact" -> {
        Matcher i = JS_IMPORT.matcher(text);
        while (i.find()) imports.add(i.group(1));
      }
      case "python" -> {
        Matcher i = PY_IMPORT.matcher(text);
        while (i.find()) imports.add(i.group(1) != null ? i.group(1) : i.group(2));
      }
      default -> {}
    }
  }

  /** The file name without its extension: for Java the name of its main type. */
  String baseName() {
    String name = file.name();
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  /**
   * The project files this one depends on, by import or (Java, Kotlin) by naming a type of the same
   * package. {@code index} maps what the other files declare to their paths.
   */
  Set<String> dependsOn(CodeIndex index) {
    Set<String> out = new LinkedHashSet<>();
    String language = file.language() == null ? "" : file.language();
    switch (language) {
      case "java", "kotlin", "scala" -> {
        for (String imp : imports) {
          if (imp.endsWith(".*")) out.addAll(index.inPackage(imp.substring(0, imp.length() - 2)));
          else {
            String path = index.byQualifiedName(imp);
            if (path == null) path = index.byQualifiedName(stripLast(imp)); // static or nested
            if (path != null) out.add(path);
          }
        }
        for (String name : mentioned) {
          String path = index.bySimpleNameInPackage(packageName, name);
          if (path != null) out.add(path);
        }
      }
      case "javascript", "javascriptreact", "typescript", "typescriptreact" -> {
        for (String imp : imports) {
          String path = index.relative(file.directory(), imp);
          if (path != null) out.add(path);
        }
      }
      case "python" -> {
        for (String imp : imports) {
          String path = index.pythonModule(imp);
          if (path != null) out.add(path);
        }
      }
      default -> {}
    }
    out.remove(file.path());
    return out;
  }

  private static String stripLast(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }
}
