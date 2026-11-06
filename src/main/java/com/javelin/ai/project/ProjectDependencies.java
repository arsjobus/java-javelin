package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short, plain list of the libraries a project declares, read from its build files with simple
 * patterns (no build tool is run and nothing is resolved). It names what is written in the files:
 * enough for a model to know what is available, not a replacement for the build.
 */
public final class ProjectDependencies {
  private ProjectDependencies() {}

  /** The most lines one build file contributes. */
  static final int MAX_PER_FILE = 40;

  private static final Pattern MAVEN_DEPENDENCY =
      Pattern.compile("(?s)<dependency>(.*?)</dependency>");
  private static final Pattern GRADLE =
      Pattern.compile(
          "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor|kapt)\\s*\\(?\\s*[\"']([^\"']+)[\"']");
  private static final Pattern NPM_SECTION =
      Pattern.compile("(?s)\"(dependencies|devDependencies)\"\\s*:\\s*\\{([^}]*)\\}");
  private static final Pattern NPM_ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern GO_REQUIRE =
      Pattern.compile(
          "(?m)^\\s*(?:require\\s+)?([\\w.\\-/]+\\.[\\w.\\-/]+)\\s+(v[\\w.\\-+]+)\\s*$");
  private static final Pattern CARGO_ENTRY =
      Pattern.compile(
          "(?m)^([A-Za-z0-9_\\-]+)\\s*=\\s*(?:\"([^\"]+)\"|\\{[^}]*version\\s*=\\s*\"([^\"]+)\")");

  /** "Maven" and so on for a build file name, or null when it is not one this reads. */
  public static String systemOf(String fileName) {
    return switch (fileName.toLowerCase(java.util.Locale.ROOT)) {
      case "pom.xml" -> "Maven";
      case "build.gradle", "build.gradle.kts" -> "Gradle";
      case "package.json" -> "npm";
      case "requirements.txt", "pyproject.toml", "setup.py" -> "Python";
      case "go.mod" -> "Go modules";
      case "cargo.toml" -> "Cargo";
      case "composer.json" -> "Composer";
      case "gemfile" -> "Bundler";
      case "makefile" -> "Make";
      case "cmakelists.txt" -> "CMake";
      default -> null;
    };
  }

  /** The declared dependencies of one build file, one per line, at most {@link #MAX_PER_FILE}. */
  public static List<String> read(String fileName, String text) {
    List<String> out = new ArrayList<>();
    switch (fileName.toLowerCase(java.util.Locale.ROOT)) {
      case "pom.xml" -> maven(text, out);
      case "build.gradle", "build.gradle.kts" -> gradle(text, out);
      case "package.json" -> npm(text, out);
      case "requirements.txt" -> requirements(text, out);
      case "go.mod" -> goMod(text, out);
      case "cargo.toml" -> cargo(text, out);
      default -> {}
    }
    return out.size() > MAX_PER_FILE ? new ArrayList<>(out.subList(0, MAX_PER_FILE)) : out;
  }

  private static void maven(String text, List<String> out) {
    Matcher m = MAVEN_DEPENDENCY.matcher(text.replaceAll("(?s)<!--.*?-->", ""));
    while (m.find()) {
      String body = m.group(1);
      String group = tag(body, "groupId");
      String artifact = tag(body, "artifactId");
      if (artifact == null) continue;
      String version = tag(body, "version");
      String scope = tag(body, "scope");
      out.add(
          (group == null ? "" : group + ":")
              + artifact
              + (version == null ? "" : " " + version)
              + (scope == null ? "" : " (" + scope + ")"));
    }
  }

  private static String tag(String body, String name) {
    Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(body);
    return m.find() && !m.group(1).isEmpty() ? m.group(1) : null;
  }

  private static void gradle(String text, List<String> out) {
    Matcher m = GRADLE.matcher(text);
    while (m.find()) out.add(m.group(2) + " (" + m.group(1) + ")");
  }

  private static void npm(String text, List<String> out) {
    Matcher section = NPM_SECTION.matcher(text);
    while (section.find()) {
      Matcher entry = NPM_ENTRY.matcher(section.group(2));
      while (entry.find())
        out.add(
            entry.group(1)
                + " "
                + entry.group(2)
                + ("devDependencies".equals(section.group(1)) ? " (dev)" : ""));
    }
  }

  private static void requirements(String text, List<String> out) {
    for (String line : text.split("\\R")) {
      String s = line.strip();
      if (s.isEmpty() || s.startsWith("#") || s.startsWith("-")) continue;
      out.add(s);
    }
  }

  private static void goMod(String text, List<String> out) {
    Matcher m = GO_REQUIRE.matcher(text);
    while (m.find()) out.add(m.group(1) + " " + m.group(2));
  }

  private static void cargo(String text, List<String> out) {
    boolean in = false;
    StringBuilder section = new StringBuilder();
    for (String line : text.split("\\R")) {
      String s = line.strip();
      if (s.startsWith("[")) {
        in = s.equals("[dependencies]") || s.equals("[dev-dependencies]");
        continue;
      }
      if (in) section.append(line).append('\n');
    }
    Matcher m = CARGO_ENTRY.matcher(section);
    while (m.find()) out.add(m.group(1) + " " + (m.group(2) != null ? m.group(2) : m.group(3)));
  }
}
