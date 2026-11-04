package com.javelin.ai;

import java.util.Locale;

/**
 * The name a generated test file starts with, by the conventions of each language. The user still
 * chooses where to save it.
 */
public final class TestFileNames {
  private TestFileNames() {}

  public static String suggest(String sourceFileName, String languageId) {
    String name = sourceFileName == null ? "" : sourceFileName.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String ext = dot > 0 ? name.substring(dot) : "";
    if (base.isEmpty()) {
      base = "Untitled";
    }
    String language = languageId == null ? "" : languageId.toLowerCase(Locale.ROOT);
    if (ext.isEmpty()) ext = defaultExtension(language);
    return switch (language) {
      case "java", "kotlin" -> pascal(stripTest(base)) + "Test" + ext;
      case "php" -> pascal(stripTest(base)) + "Test" + ext;
      case "python" -> "test_" + snake(base) + ext;
      case "javascript", "javascriptreact", "typescript", "typescriptreact" -> base + ".test" + ext;
      case "go", "rust", "ruby", "c", "cpp", "shellscript" -> snake(base) + "_test" + ext;
      default -> base + ".test" + ext;
    };
  }

  private static String stripTest(String base) {
    return base.endsWith("Test") && base.length() > 4 ? base.substring(0, base.length() - 4) : base;
  }

  private static String pascal(String base) {
    return base.isEmpty() ? base : Character.toUpperCase(base.charAt(0)) + base.substring(1);
  }

  private static String snake(String base) {
    return base.replace('-', '_').replace(' ', '_');
  }

  private static String defaultExtension(String language) {
    return switch (language) {
      case "java" -> ".java";
      case "kotlin" -> ".kt";
      case "python" -> ".py";
      case "javascript" -> ".js";
      case "typescript" -> ".ts";
      case "go" -> ".go";
      case "rust" -> ".rs";
      case "php" -> ".php";
      case "ruby" -> ".rb";
      case "c" -> ".c";
      case "cpp" -> ".cpp";
      case "shellscript" -> ".sh";
      default -> ".txt";
    };
  }
}
