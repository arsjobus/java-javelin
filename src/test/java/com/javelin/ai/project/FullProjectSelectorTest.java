package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullProjectSelectorTest {
  @TempDir Path dir;

  @Test
  void everyEligibleFileIsPickedExactlyOnceWithAReason() throws IOException {
    ProjectFixture.miniEditor(dir);
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    List<Pick> picks = FullProjectSelector.select(index);
    assertEquals(index.files().size(), picks.size());
    assertEquals(index.files().size(), picks.stream().map(Pick::path).distinct().count());
    assertTrue(picks.stream().noneMatch(p -> p.reason().isBlank()));
    assertEquals("project documentation", picks.get(0).reason());
    assertEquals("AGENTS.md", picks.get(0).path());
  }

  @Test
  void groupsAreDocumentsBuildSourceTestsConfigurationOtherDocumentsThenTheRest()
      throws IOException {
    ProjectFixture.write(dir, "README.md", "r");
    ProjectFixture.write(dir, "pom.xml", "<project/>");
    ProjectFixture.write(dir, "src/A.java", "class A {}");
    ProjectFixture.write(dir, "test/ATest.java", "class ATest {}");
    ProjectFixture.write(dir, "conf/app.yaml", "a: 1");
    ProjectFixture.write(dir, "notes/todo.md", "t");
    ProjectFixture.write(dir, "data/blob.weird", "x");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertEquals(
        List.of(
            "README.md",
            "pom.xml",
            "src/A.java",
            "test/ATest.java",
            "conf/app.yaml",
            "notes/todo.md",
            "data/blob.weird"),
        FullProjectSelector.select(index).stream().map(Pick::path).toList());
    assertEquals(
        List.of(
            "project documentation",
            "build configuration",
            "source",
            "test",
            "configuration",
            "documentation",
            "project file"),
        FullProjectSelector.select(index).stream().map(Pick::reason).toList());
  }

  @Test
  void docsFolderFilesCountAsOverviewDocumentsAndOrderInsideAGroupIsByPath() throws IOException {
    ProjectFixture.write(dir, "docs/guide.md", "g");
    ProjectFixture.write(dir, "src/B.java", "class B {}");
    ProjectFixture.write(dir, "src/A.java", "class A {}");
    List<String> paths =
        FullProjectSelector.select(ProjectScanner.scan(dir, ScanOptions.defaults())).stream()
            .map(Pick::path)
            .toList();
    assertEquals(List.of("docs/guide.md", "src/A.java", "src/B.java"), paths);
  }
}
