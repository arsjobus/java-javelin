package com.javelin.ai.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A tiny Maven-shaped project on disk, and a way to prove a check did not touch it. */
final class VerifyFixtures {
  private VerifyFixtures() {}

  static final String CALC =
      "package demo;\n\n"
          + "public class Calc {\n"
          + "  public int add(int a, int b) {\n"
          + "    return a - b;\n"
          + "  }\n"
          + "}\n";
  static final String CALC_FIXED =
      "package demo;\n\n"
          + "public class Calc {\n"
          + "  public int add(int a, int b) {\n"
          + "    return a + b;\n"
          + "  }\n"
          + "}\n";
  static final String USER =
      "package demo;\n\n"
          + "public class User {\n"
          + "  int three() {\n"
          + "    return new Calc().add(1, 2);\n"
          + "  }\n"
          + "}\n";
  static final String CALC_TEST =
      "package demo;\n\n"
          + "import org.junit.jupiter.api.Test;\n"
          + "import static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
          + "class CalcTest {\n"
          + "  @Test\n"
          + "  void adds() {\n"
          + "    assertEquals(5, new Calc().add(2, 3));\n"
          + "  }\n"
          + "}\n";

  static Path project(Path dir) throws IOException {
    write(dir, "src/main/java/demo/Calc.java", CALC);
    write(dir, "src/main/java/demo/User.java", USER);
    write(dir, "src/test/java/demo/CalcTest.java", CALC_TEST);
    return dir;
  }

  static void write(Path root, String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  /** Every file under {@code root} with its content and modification time. */
  static Map<String, String> snapshot(Path root) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> all = new ArrayList<>(walk.sorted().toList());
      for (Path p : all)
        out.put(
            root.relativize(p).toString(),
            Files.isRegularFile(p)
                ? Files.getLastModifiedTime(p).toMillis() + ":" + Files.readString(p)
                : "dir");
    }
    return out;
  }

  /**
   * The JUnit jars of the JVM running these tests, which the fixture project may compile against.
   */
  static List<Path> junitJars() {
    List<Path> jars = new ArrayList<>();
    for (String name :
        List.of(
            "org.junit.jupiter.api.Test",
            "org.opentest4j.AssertionFailedError",
            "org.junit.platform.commons.JUnitException",
            "org.apiguardian.api.API")) {
      try {
        Path p =
            Path.of(
                Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!jars.contains(p)) jars.add(p);
      } catch (Exception ex) {
        // optional
      }
    }
    return jars;
  }

  static ProjectClasspath.Resolved withJunit() {
    return new ProjectClasspath.Resolved(junitJars(), true, "");
  }
}
