package com.javelin.ai.project;

import com.javelin.language.Languages;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Decides a file's language and {@link ProjectFile.Kind} from its path alone. JavaFX-free. */
public final class FileKinds {
  private FileKinds() {}

  private static final Map<String, String> EXTRA_LANGUAGES =
      Map.ofEntries(
          Map.entry("md", "markdown"),
          Map.entry("markdown", "markdown"),
          Map.entry("rst", "restructuredtext"),
          Map.entry("adoc", "asciidoc"),
          Map.entry("txt", "text"),
          Map.entry("xml", "xml"),
          Map.entry("fxml", "xml"),
          Map.entry("yml", "yaml"),
          Map.entry("yaml", "yaml"),
          Map.entry("toml", "toml"),
          Map.entry("properties", "properties"),
          Map.entry("conf", "conf"),
          Map.entry("ini", "ini"),
          Map.entry("cfg", "ini"),
          Map.entry("gradle", "groovy"),
          Map.entry("groovy", "groovy"),
          Map.entry("scala", "scala"),
          Map.entry("swift", "swift"),
          Map.entry("cs", "csharp"),
          Map.entry("sql", "sql"),
          Map.entry("css", "css"),
          Map.entry("scss", "scss"),
          Map.entry("html", "html"),
          Map.entry("htm", "html"),
          Map.entry("vue", "vue"),
          Map.entry("svelte", "svelte"));

  private static final Set<String> SOURCE_LANGUAGES =
      Set.of(
          "java",
          "python",
          "javascript",
          "javascriptreact",
          "typescript",
          "typescriptreact",
          "go",
          "rust",
          "c",
          "cpp",
          "kotlin",
          "ruby",
          "php",
          "shellscript",
          "groovy",
          "scala",
          "swift",
          "csharp",
          "sql",
          "css",
          "scss",
          "html",
          "vue",
          "svelte");

  private static final Set<String> CONFIG_LANGUAGES =
      Set.of("json", "xml", "yaml", "toml", "properties", "conf", "ini");

  private static final Set<String> BUILD_FILES =
      Set.of(
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "settings.gradle",
          "settings.gradle.kts",
          "gradle.properties",
          "package.json",
          "tsconfig.json",
          "cargo.toml",
          "go.mod",
          "requirements.txt",
          "pyproject.toml",
          "setup.py",
          "makefile",
          "cmakelists.txt",
          "gemfile",
          "composer.json");

  private static final Pattern TEST_NAME =
      Pattern.compile(
          "(?:.*[A-Za-z0-9](?:Test|Tests|IT|Spec)\\.(?:java|kt|scala|groovy)|(?:Test|Tests)\\.(?:java|kt)|test_.*\\.py|.*_test\\.(?:py|go|rs|rb|c|cpp)|.*\\.(?:test|spec)\\.(?:js|jsx|ts|tsx|mjs))");

  /** The language id for a path, or null when it is not a recognised kind of text. */
  public static String languageOf(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    String lower = name.toLowerCase(Locale.ROOT);
    if (BUILD_FILES.contains(lower) && lower.equals("makefile")) return "makefile";
    String id = Languages.idFor(new File(name));
    if (id != null) return id;
    int dot = lower.lastIndexOf('.');
    return dot < 0 ? null : EXTRA_LANGUAGES.get(lower.substring(dot + 1));
  }

  public static ProjectFile.Kind kindOf(String path, String language) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    String lower = name.toLowerCase(Locale.ROOT);
    if (BUILD_FILES.contains(lower)) return ProjectFile.Kind.BUILD;
    if (isTest(path)) return ProjectFile.Kind.TEST;
    if ("markdown".equals(language)
        || "restructuredtext".equals(language)
        || "asciidoc".equals(language)
        || "text".equals(language)
        || lower.startsWith("readme")
        || lower.startsWith("license")
        || lower.startsWith("changelog")) return ProjectFile.Kind.DOC;
    if (language != null && SOURCE_LANGUAGES.contains(language)) return ProjectFile.Kind.SOURCE;
    if (language != null && CONFIG_LANGUAGES.contains(language)) return ProjectFile.Kind.CONFIG;
    if (lower.equals("dockerfile") || lower.startsWith(".")) return ProjectFile.Kind.CONFIG;
    return ProjectFile.Kind.OTHER;
  }

  /** True for a path that holds tests, by folder or by the usual file name of each language. */
  public static boolean isTest(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    String name = path.substring(path.lastIndexOf('/') + 1);
    return lower.startsWith("test/")
        || lower.startsWith("tests/")
        || lower.contains("/test/")
        || lower.contains("/tests/")
        || lower.contains("/__tests__/")
        || TEST_NAME.matcher(name).matches();
  }
}
