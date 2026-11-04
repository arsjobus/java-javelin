package com.javelin.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The code lives in one package, {@code com.javelin}, and everything that names it agrees: sources
 * and tests, the POM's group and main class, the packaging scripts and the documents. The old
 * spelling with an underscore must not come back by a stale import, a copied file or a script that
 * still starts the old class.
 */
class PackageNameTest {
  private static final String OLD =
      "jav" + "elin_ide"; // spelled in two parts so that this file is not a hit itself
  private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^package\\s+([\\w.]+);");

  /**
   * A regular file the check should read: not macOS metadata ({@code ._name} AppleDouble files that
   * appear next to files copied to a non-Mac drive, and {@code .DS_Store}), which is binary and can
   * sit beside source files with the same extension.
   */
  static boolean isCheckable(Path file) {
    String name = file.getFileName().toString();
    return Files.isRegularFile(file) && !name.startsWith("._") && !name.equals(".DS_Store");
  }

  /**
   * The text of a file; bytes that are not UTF-8 are replaced rather than failing the whole check.
   */
  static String read(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  private static List<Path> files(Path root, String suffix) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(PackageNameTest::isCheckable)
          .filter(p -> p.toString().endsWith(suffix))
          .sorted()
          .toList();
    }
  }

  @Test
  void everySourceAndTestIsInThePackageOrASubpackageOfIt() throws IOException {
    List<String> wrong = new ArrayList<>();
    for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
      for (Path file : files(root, ".java")) {
        var matcher = PACKAGE_LINE.matcher(read(file));
        String declared = matcher.find() ? matcher.group(1) : "(none)";
        String expected =
            root.relativize(file.getParent()).toString().replace('/', '.').replace('\\', '.');
        if (!declared.equals(expected)
            || !(declared.equals("com.javelin") || declared.startsWith("com.javelin.")))
          wrong.add(file + " declares " + declared);
      }
    }
    assertEquals(List.of(), wrong);
  }

  @Test
  void noDirectoryOrImportKeepsTheOldName() throws IOException {
    assertFalse(Files.exists(Path.of("src/main/java/com/" + OLD)));
    assertFalse(Files.exists(Path.of("src/test/java/com/" + OLD)));
    List<String> stale = new ArrayList<>();
    for (Path root : List.of(Path.of("src"), Path.of("packaging"), Path.of("."))) {
      try (Stream<Path> walk =
          Files.walk(root, root.equals(Path.of(".")) ? 1 : Integer.MAX_VALUE)) {
        for (Path file :
            walk.filter(PackageNameTest::isCheckable)
                .filter(
                    p ->
                        p.toString().matches(".*\\.(java|md|xml|sh|css|command)$")
                            || p.getParent().endsWith("macos"))
                .toList()) {
          for (String line : read(file).split("\\R")) {
            if (line.contains("com." + OLD)
                || line.contains("com/" + OLD)
                || line.contains("<artifactId>" + OLD)) stale.add(file + ": " + line.strip());
          }
        }
      }
    }
    assertEquals(List.of(), stale);
  }

  @Test
  void thePomAndThePackagingScriptsStartTheClassesTheyShouldStart() throws IOException {
    String pom = read(Path.of("pom.xml"));
    assertTrue(pom.contains("<groupId>com.javelin</groupId>"));
    assertTrue(pom.contains("<mainClass>com.javelin.App</mainClass>"));
    assertTrue(Files.isRegularFile(Path.of("src/main/java/com/javelin/App.java")));
    assertTrue(Files.isRegularFile(Path.of("src/main/java/com/javelin/Launcher.java")));
    String build = read(Path.of("packaging/macos/build-app.sh"));
    assertTrue(build.contains("--main-class com.javelin.Launcher"));
    assertTrue(build.contains("com.javelin.icon.IconExporter"));
    assertTrue(Files.isRegularFile(Path.of("src/main/java/com/javelin/icon/IconExporter.java")));
  }
}
