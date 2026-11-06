package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectDependenciesTest {
  @Test
  void readsMavenDependenciesAndIgnoresComments() {
    String pom =
        "<project><!-- <dependency><artifactId>commented</artifactId></dependency> -->"
            + "<dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version><scope>test</scope></dependency>"
            + "<dependency><artifactId>bare</artifactId></dependency>"
            + "<dependency><groupId>x</groupId></dependency></dependencies></project>";
    assertEquals(List.of("g:a 1.0 (test)", "bare"), ProjectDependencies.read("pom.xml", pom));
  }

  @Test
  void readsGradleNpmPythonGoAndCargo() {
    assertEquals(
        List.of("org.x:y:1 (implementation)", "z:w (testImplementation)"),
        ProjectDependencies.read(
            "build.gradle",
            "dependencies {\n  implementation 'org.x:y:1'\n  testImplementation(\"z:w\")\n}\n"));
    assertEquals(
        List.of("left-pad ^1.0", "jest 29 (dev)"),
        ProjectDependencies.read(
            "package.json",
            "{\"dependencies\": {\"left-pad\": \"^1.0\"}, \"devDependencies\": {\"jest\":"
                + " \"29\"}}"));
    assertEquals(
        List.of("flask==2.0", "requests"),
        ProjectDependencies.read(
            "requirements.txt", "# c\nflask==2.0\n\n-r other.txt\nrequests\n"));
    assertEquals(
        List.of("github.com/a/b v1.2.3", "golang.org/x/text v0.3.0"),
        ProjectDependencies.read(
            "go.mod",
            "module m\n\n"
                + "require github.com/a/b v1.2.3\n"
                + "require (\n"
                + "  golang.org/x/text v0.3.0\n"
                + ")\n"));
    assertEquals(
        List.of("serde 1.0", "tokio 1"),
        ProjectDependencies.read(
            "Cargo.toml",
            "[package]\n"
                + "name = \"x\"\n"
                + "[dependencies]\n"
                + "serde = \"1.0\"\n"
                + "tokio = { version = \"1\", features = [] }\n"
                + "[other]\n"
                + "ignored = \"9\"\n"));
  }

  @Test
  void aBuildFileWithNothingDeclaredOrAnUnknownOneGivesNothing() {
    assertTrue(ProjectDependencies.read("pom.xml", "<project/>").isEmpty());
    assertTrue(ProjectDependencies.read("Makefile", "all:\n").isEmpty());
    assertTrue(ProjectDependencies.read("package.json", "{}").isEmpty());
  }

  @Test
  void oneFileContributesAtMostAFixedNumberOfLines() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) text.append("pkg").append(i).append("\n");
    assertEquals(
        ProjectDependencies.MAX_PER_FILE,
        ProjectDependencies.read("requirements.txt", text.toString()).size());
  }

  @Test
  void buildSystemsAreNamedByFile() {
    assertEquals("Maven", ProjectDependencies.systemOf("pom.xml"));
    assertEquals("Gradle", ProjectDependencies.systemOf("build.gradle.kts"));
    assertEquals("npm", ProjectDependencies.systemOf("package.json"));
    assertEquals("Python", ProjectDependencies.systemOf("pyproject.toml"));
    assertEquals("Go modules", ProjectDependencies.systemOf("go.mod"));
    assertEquals("Cargo", ProjectDependencies.systemOf("Cargo.toml"));
    assertEquals("Make", ProjectDependencies.systemOf("Makefile"));
    assertEquals("CMake", ProjectDependencies.systemOf("CMakeLists.txt"));
    assertEquals("Composer", ProjectDependencies.systemOf("composer.json"));
    assertEquals("Bundler", ProjectDependencies.systemOf("Gemfile"));
    assertNull(ProjectDependencies.systemOf("README.md"));
  }
}
