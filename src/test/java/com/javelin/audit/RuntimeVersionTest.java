package com.javelin.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Starting Javelin must not print JDK warnings. The ones seen on Java 21 with JavaFX 21 came from
 * JavaFX 21 itself (a restricted native-library call, and {@code sun.misc.Unsafe} in its renderer),
 * so the cure is a newer JavaFX on a JDK it supports, not a flag that hides the message. These
 * tests read the real POM, scripts and documents, so the versions, the native-access grant and the
 * module-path packaging cannot drift apart unnoticed.
 */
class RuntimeVersionTest {
  /** The long-term-support JDK. JavaFX 25 itself needs JDK 23 or newer. */
  private static final int JAVA = 25;

  private static final int JAVAFX = 25;
  private static final Path SCRIPTS = Path.of("packaging", "macos");

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static String pom() throws IOException {
    return read(Path.of("pom.xml"));
  }

  private static String property(String name) throws IOException {
    Matcher m =
        Pattern.compile("<" + Pattern.quote(name) + ">([^<]+)</" + Pattern.quote(name) + ">")
            .matcher(pom());
    assertTrue(m.find(), "pom.xml defines " + name);
    return m.group(1).strip();
  }

  /** The text of the {@code <plugin>} block for one artifact. */
  private static String plugin(String artifactId) throws IOException {
    Matcher m =
        Pattern.compile(
                "(?s)<plugin>(?:(?!</plugin>).)*?<artifactId>"
                    + Pattern.quote(artifactId)
                    + "</artifactId>.*?</plugin>")
            .matcher(pom());
    assertTrue(m.find(), "pom.xml configures " + artifactId);
    return m.group();
  }

  static int major(String version) {
    return Integer.parseInt(version.split("[.\\-+]")[0]);
  }

  @Test
  void theMajorVersionIsReadFromVersionStrings() {
    assertEquals(25, major("25"));
    assertEquals(25, major("25.0.2"));
    assertEquals(27, major("27-ea+25"));
    assertEquals(21, major("21.0.6"));
  }

  @Test
  void theProjectIsBuiltForTheCurrentLongTermSupportJava() throws IOException {
    assertTrue(
        major(property("maven.compiler.release")) >= JAVA,
        "release " + property("maven.compiler.release"));
  }

  @Test
  void javaFxIsNewEnoughToStartWithoutTheUnsafeWarning() throws IOException {
    // JavaFX 25 replaced sun.misc.Unsafe in its renderer (JDK-8334137); JavaFX 21 to 24 print the
    // warning on JDK 24 and up.
    assertTrue(
        major(property("javafx.version")) >= JAVAFX,
        "javafx.version " + property("javafx.version"));
  }

  @Test
  void theJdkIsNewEnoughForTheJavaFxItRuns() throws IOException {
    // JavaFX 25 is compiled for JDK 23 and cannot be read by anything older.
    int minimumJdk = major(property("javafx.version")) >= 25 ? 23 : 17;
    assertTrue(major(property("maven.compiler.release")) >= minimumJdk);
  }

  @Test
  void mavenRunGrantsNativeAccessToJavaFxOnly() throws IOException {
    String javafx = plugin("javafx-maven-plugin");
    assertTrue(
        javafx.contains("<option>--enable-native-access=javafx.graphics</option>"),
        "javafx:run must grant native access to javafx.graphics");
    assertFalse(
        javafx.contains("ALL-UNNAMED"),
        "javafx:run puts JavaFX on the module path; do not open native access to every module");
  }

  @Test
  void theTestJvmLoadsJavaFxAsNamedModulesAndGrantsItNativeAccess() throws IOException {
    String surefire = plugin("maven-surefire-plugin");
    assertTrue(
        surefire.contains("--module-path ${project.build.directory}/test-fx"),
        "JavaFX goes on the test JVM's module path");
    assertTrue(surefire.contains("--add-modules javafx.controls"));
    assertTrue(surefire.contains("--enable-native-access=javafx.graphics"));
    assertFalse(
        surefire.contains("ALL-UNNAMED"),
        "JavaFX is a named module in the tests too; do not open native access to everything");
    for (String module : new String[] {"javafx-base", "javafx-graphics", "javafx-controls"}) {
      assertTrue(
          surefire.contains(
              "<classpathDependencyExclude>org.openjfx:"
                  + module
                  + "</classpathDependencyExclude>"),
          module + " stays off the class path");
    }
    String copy = plugin("maven-dependency-plugin");
    assertTrue(
        copy.contains("${project.build.directory}/test-fx")
            && copy.contains("<includeGroupIds>org.openjfx</includeGroupIds>"));
    assertFalse(
        copy.contains("<includeClassifiers></includeClassifiers>"),
        "the class-less stubs must not be copied beside the platform jars");
  }

  @Test
  void javaFxInTheTestJvmIsANamedModule() {
    // Fails when Surefire falls back to the class path, which is what prints "Unsupported JavaFX
    // configuration".
    assertTrue(
        javafx.application.Platform.class.getModule().isNamed(),
        "JavaFX loaded from the class path");
    assertEquals("javafx.graphics", javafx.application.Platform.class.getModule().getName());
  }

  @Test
  void theMacAppLoadsJavaFxAsModulesAndGrantsItNativeAccess() throws IOException {
    String build = read(SCRIPTS.resolve("build-app.sh"));
    assertTrue(build.contains("--module-path target/fx"), "JavaFX goes on the module path");
    assertTrue(build.contains("--add-modules \"$MODULES\""));
    assertTrue(
        build.contains("MODULES=\"javafx.controls,"),
        "the runtime is told which modules to include");
    assertTrue(build.contains("--java-options \"--enable-native-access=javafx.graphics\""));
    assertTrue(build.contains("-lt 25"), "an older JDK is refused");
  }

  @Test
  void noFlagIsUsedToHideAWarning() throws IOException {
    for (String text :
        new String[] {pom(), read(SCRIPTS.resolve("build-app.sh")), read(Path.of("run.command"))}) {
      assertFalse(
          text.contains("sun-misc-unsafe-memory-access"),
          "JavaFX 25 does not need it; the warning is fixed, not hidden");
      assertFalse(
          text.contains("illegal-native-access=allow"),
          "native access is granted to JavaFX by name");
    }
  }

  @Test
  void theDocumentsNameTheJavaVersionTheBuildNeeds() throws IOException {
    for (String file : new String[] {"README.md", "AGENTS.md"})
      assertTrue(read(Path.of(file)).contains("Java " + JAVA + "+"), file + " names Java " + JAVA);
    assertTrue(read(SCRIPTS.resolve("build-app.sh")).contains("JDK " + JAVA + "+"));
    assertTrue(read(Path.of("run.command")).contains("JDK " + JAVA + " or newer"));
  }
}
