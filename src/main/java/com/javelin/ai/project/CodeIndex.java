package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Where each type or module of the project lives, so an import can be followed to its file. */
final class CodeIndex {
  private final Map<String, String> byQualified = new HashMap<>();
  private final Map<String, List<String>> byPackage = new TreeMap<>();
  private final Map<String, String> paths = new HashMap<>();

  CodeIndex(Iterable<CodeFacts> facts) {
    for (CodeFacts f : facts) {
      paths.put(f.file.path(), f.file.path());
      String language = f.file.language() == null ? "" : f.file.language();
      if (language.equals("java") || language.equals("kotlin") || language.equals("scala")) {
        String qualified =
            f.packageName.isEmpty() ? f.baseName() : f.packageName + "." + f.baseName();
        byQualified.put(qualified, f.file.path());
        byPackage.computeIfAbsent(f.packageName, k -> new ArrayList<>()).add(f.file.path());
      }
    }
  }

  String byQualifiedName(String name) {
    return byQualified.get(name);
  }

  List<String> inPackage(String packageName) {
    return byPackage.getOrDefault(packageName, List.of());
  }

  String bySimpleNameInPackage(String packageName, String simpleName) {
    return byQualified.get(packageName.isEmpty() ? simpleName : packageName + "." + simpleName);
  }

  /** A relative import from {@code directory}: "./x" may be x.js, x.ts, x/index.js and so on. */
  String relative(String directory, String target) {
    String base = normalize(directory.isEmpty() ? target : directory + "/" + target);
    if (base == null) return null;
    for (String suffix :
        List.of("", ".js", ".jsx", ".ts", ".tsx", ".mjs", "/index.js", "/index.ts", "/index.tsx")) {
      if (paths.containsKey(base + suffix)) return base + suffix;
    }
    return null;
  }

  /** {@code a.b.c} is a/b/c.py or a/b/c/__init__.py, at the top of the project or under src/. */
  String pythonModule(String module) {
    String path = module.replace('.', '/');
    for (String root : List.of("", "src/"))
      for (String suffix : List.of(".py", "/__init__.py"))
        if (paths.containsKey(root + path + suffix)) return root + path + suffix;
    return null;
  }

  private static String normalize(String path) {
    List<String> out = new ArrayList<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (out.isEmpty()) return null;
        out.remove(out.size() - 1);
      } else out.add(part);
    }
    return String.join("/", out);
  }
}
