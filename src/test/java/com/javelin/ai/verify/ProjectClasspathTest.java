package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectClasspathTest {
  @TempDir Path dir;

  private Path repo() throws IOException {
    return Files.createDirectories(dir.resolve("repo"));
  }

  private Path project() throws IOException {
    return Files.createDirectories(dir.resolve("project"));
  }

  private static void jar(Path repo, String group, String artifact, String version)
      throws IOException {
    Path jar =
        repo.resolve(group.replace('.', '/'))
            .resolve(artifact)
            .resolve(version)
            .resolve(artifact + "-" + version + ".jar");
    Files.createDirectories(jar.getParent());
    Files.write(jar, new byte[] {1});
  }

  private static String pom(String deps, String properties) {
    return "<project><properties>"
        + properties
        + "</properties><dependencies>"
        + deps
        + "</dependencies></project>";
  }

  private static String dep(String g, String a, String v) {
    return "<dependency><groupId>"
        + g
        + "</groupId><artifactId>"
        + a
        + "</artifactId>"
        + (v == null ? "" : "<version>" + v + "</version>")
        + "</dependency>";
  }

  @Test
  void aProjectWithNoBuildFileIsCompleteAndEmpty() throws IOException {
    var r = ProjectClasspath.resolve(project(), repo());
    assertTrue(r.complete());
    assertTrue(r.entries().isEmpty());
  }

  @Test
  void buildOutputAndLibJarsAreUsed() throws IOException {
    Path p = project();
    Files.createDirectories(p.resolve("target/classes"));
    Files.createDirectories(p.resolve("lib"));
    Files.write(p.resolve("lib/a.jar"), new byte[] {1});
    var r = ProjectClasspath.resolve(p, repo());
    assertEquals(2, r.entries().size());
    assertTrue(r.complete());
  }

  @Test
  void directMavenDependenciesAreFoundInTheLocalRepository() throws IOException {
    Path p = project(), repo = repo();
    jar(repo, "org.example", "lib", "1.2");
    jar(repo, "org.other", "tool", "3.0");
    Files.writeString(
        p.resolve("pom.xml"),
        pom(
            dep("org.example", "lib", "1.2") + dep("org.other", "tool", "${tool.version}"),
            "<tool.version>3.0</tool.version>"));
    var r = ProjectClasspath.resolve(p, repo);
    assertTrue(r.complete(), r.note());
    assertEquals(2, r.entries().size());
  }

  @Test
  void aDependencyThatIsNotThereMakesTheClassPathIncomplete() throws IOException {
    Path p = project();
    Files.writeString(p.resolve("pom.xml"), pom(dep("org.missing", "gone", "9.9"), ""));
    var r = ProjectClasspath.resolve(p, repo());
    assertFalse(r.complete());
    assertTrue(r.note().contains("org.missing:gone:9.9"), r.note());
  }

  @Test
  void aVersionOnlyInDependencyManagementIsNotGuessed() throws IOException {
    Path p = project();
    Files.writeString(
        p.resolve("pom.xml"),
        "<project><dependencyManagement><dependencies>"
            + dep("a", "b", "1")
            + "</dependencies></dependencyManagement>"
            + "<dependencies>"
            + dep("a", "b", null)
            + "</dependencies></project>");
    var r = ProjectClasspath.resolve(p, repo());
    assertFalse(r.complete());
    assertTrue(r.note().contains("version not written"));
  }

  @Test
  void pluginDependenciesAndCommentsAreIgnored() throws IOException {
    Path p = project();
    Files.writeString(
        p.resolve("pom.xml"),
        "<project><!-- "
            + dep("x", "commented", "1")
            + " --><build><plugins><plugin><dependencies>"
            + dep("x", "plugin-dep", "1")
            + "</dependencies></plugin></plugins></build></project>");
    assertTrue(ProjectClasspath.resolve(p, repo()).complete());
  }

  @Test
  void aGradleProjectWithDependenciesIsNotGuessedAt() throws IOException {
    Path p = project();
    Files.writeString(p.resolve("build.gradle"), "dependencies {\n  implementation 'a:b:1'\n}\n");
    assertFalse(ProjectClasspath.resolve(p, repo()).complete());
  }
}
