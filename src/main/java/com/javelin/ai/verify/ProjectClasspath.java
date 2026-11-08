package com.javelin.ai.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds what a project's Java needs on the class path without running a build tool and without the
 * network: build output folders, jars in {@code lib/}, and the direct Maven dependencies of the
 * {@code pom.xml} that are already in the local repository. Transitive dependencies are not
 * resolved. When a declared dependency cannot be found the result says {@code complete == false}
 * and the verifier reports "not checked" rather than blaming the model for missing libraries.
 */
public final class ProjectClasspath {
  private ProjectClasspath() {}

  public record Resolved(List<Path> entries, boolean complete, String note) {
    public Resolved {
      entries = List.copyOf(entries);
    }
  }

  private static final Pattern DEPENDENCY = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
  private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

  /** The default local Maven repository, or null when the user has none. */
  public static Path defaultRepository() {
    String home = System.getProperty("user.home");
    if (home == null) return null;
    Path repo = Path.of(home, ".m2", "repository");
    return Files.isDirectory(repo) ? repo : null;
  }

  public static Resolved resolve(Path root, Path mavenRepository) {
    Set<Path> entries = new LinkedHashSet<>();
    for (String dir :
        List.of(
            "target/classes",
            "target/test-classes",
            "build/classes/java/main",
            "build/classes/java/test")) {
      Path p = root.resolve(dir);
      if (Files.isDirectory(p)) entries.add(p);
    }
    Path lib = root.resolve("lib");
    if (Files.isDirectory(lib)) {
      try (Stream<Path> walk = Files.walk(lib, 3)) {
        walk.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(entries::add);
      } catch (IOException ex) {
        // lib/ unreadable: the same as having none
      }
    }
    List<String> missing = new ArrayList<>();
    Path pom = root.resolve("pom.xml");
    if (Files.isRegularFile(pom)) mavenDependencies(pom, mavenRepository, entries, missing);
    boolean gradle =
        Files.isRegularFile(root.resolve("build.gradle"))
            || Files.isRegularFile(root.resolve("build.gradle.kts"));
    if (gradle && !Files.isRegularFile(pom) && gradleDeclaresDependencies(root))
      missing.add("Gradle dependencies (not resolved by Javelin)");
    if (!missing.isEmpty())
      return new Resolved(
          List.copyOf(entries),
          false,
          "the class path is not known: "
              + String.join(", ", missing.subList(0, Math.min(3, missing.size())))
              + (missing.size() > 3 ? " and " + (missing.size() - 3) + " more" : "")
              + " not found locally");
    return new Resolved(List.copyOf(entries), true, "");
  }

  private static boolean gradleDeclaresDependencies(Path root) {
    for (String name : List.of("build.gradle", "build.gradle.kts")) {
      Path file = root.resolve(name);
      if (!Files.isRegularFile(file)) continue;
      try {
        if (Pattern.compile("(?m)^\\s*(implementation|api|compileOnly|testImplementation)\\b")
            .matcher(Files.readString(file))
            .find()) return true;
      } catch (IOException ex) {
        return true;
      }
    }
    return false;
  }

  private static void mavenDependencies(
      Path pom, Path repository, Set<Path> entries, List<String> missing) {
    String text;
    try {
      text = Files.readString(pom);
    } catch (IOException ex) {
      missing.add("pom.xml (unreadable)");
      return;
    }
    text = text.replaceAll("(?s)<!--.*?-->", "");
    Map<String, String> properties = new HashMap<>();
    Matcher props = Pattern.compile("(?s)<properties>(.*?)</properties>").matcher(text);
    if (props.find()) {
      Matcher each = Pattern.compile("<([\\w.\\-]+)>([^<]*)</\\1>").matcher(props.group(1));
      while (each.find()) properties.put(each.group(1), each.group(2).strip());
    }
    String body =
        text.replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "")
            .replaceAll("(?s)<build>.*?</build>", "")
            .replaceAll("(?s)<profiles>.*?</profiles>", "");
    Matcher m = DEPENDENCY.matcher(body);
    while (m.find()) {
      String block = m.group(1);
      String group = tag(block, "groupId"), artifact = tag(block, "artifactId");
      String version = substitute(tag(block, "version"), properties);
      if (group == null || artifact == null) continue;
      String id = group + ":" + artifact;
      if (version == null || version.contains("${")) {
        missing.add(id + " (version not written in the pom)");
        continue;
      }
      Path jar =
          repository == null
              ? null
              : repository
                  .resolve(group.replace('.', '/'))
                  .resolve(artifact)
                  .resolve(version)
                  .resolve(artifact + "-" + version + ".jar");
      if (jar != null && Files.isRegularFile(jar)) entries.add(jar);
      else missing.add(id + ":" + version);
    }
  }

  private static String tag(String block, String name) {
    Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(block);
    return m.find() ? m.group(1) : null;
  }

  private static String substitute(String value, Map<String, String> properties) {
    if (value == null) return null;
    Matcher m = PROPERTY.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find())
      m.appendReplacement(
          out, Matcher.quoteReplacement(properties.getOrDefault(m.group(1), m.group())));
    m.appendTail(out);
    return out.toString();
  }
}
