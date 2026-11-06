package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSnapshotTest {
  @TempDir Path dir;
  ProjectWorkspace ws;
  ProjectContext context;
  static final ContextBudget BIG = new ContextBudget(0, 200_000, 4_000);

  @BeforeEach
  void setUp() throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    context =
        ContextBuilder.build(
            ws.index(),
            ws.reader(),
            ContextMode.SMART,
            List.of(new Pick("README.md", "r", 1), new Pick("src/main/java/demo/App.java", "a", 1)),
            Adjustments.NONE,
            BIG,
            100,
            ws.options(),
            7);
  }

  @Test
  void anUntouchedProjectHasNoChanges() throws IOException {
    ProjectRefresh.Result r =
        ProjectRefresh.check(context.snapshot(), ws.index(), ws.reader(), ws.options());
    assertTrue(r.changes().isEmpty());
    assertEquals("", r.changes().describe());
    assertTrue(r.changedSentFiles().isEmpty());
    assertFalse(r.isStale());
  }

  @Test
  void addedModifiedAndDeletedFilesAreTold() throws IOException {
    ProjectFixture.write(dir, "NEW.md", "n");
    Files.writeString(dir.resolve("README.md"), "# changed\nmore text\n");
    Files.delete(dir.resolve("src/main/java/demo/util/Strings.java"));
    ProjectRefresh.Result r =
        ProjectRefresh.check(context.snapshot(), ws.index(), ws.reader(), ws.options());
    assertEquals(List.of("NEW.md"), r.changes().added());
    assertEquals(List.of("README.md"), r.changes().modified());
    assertEquals(List.of("src/main/java/demo/util/Strings.java"), r.changes().deleted());
    assertEquals("1 modified, 1 added, 1 deleted", r.changes().describe());
    assertTrue(r.changes().touches(context.snapshot()), "README was sent and changed");
    assertEquals(List.of("README.md"), r.changedSentFiles());
    assertTrue(r.isStale());
  }

  @Test
  void changingAFileThatWasNotSentDoesNotMakeTheAnswerStale() throws IOException {
    Files.writeString(dir.resolve("src/main/java/demo/util/Strings.java"), "package demo.util;\n");
    ProjectRefresh.Result r =
        ProjectRefresh.check(context.snapshot(), ws.index(), ws.reader(), ws.options());
    assertFalse(r.changes().isEmpty());
    assertFalse(r.changes().touches(context.snapshot()));
    assertFalse(r.isStale());
  }

  @Test
  void anEditThatKeepsSizeAndTimeIsStillCaughtByContent() throws IOException {
    Path readme = dir.resolve("README.md");
    FileTime time = Files.getLastModifiedTime(readme);
    String same = Files.readString(readme).replace("small", "SMALL");
    Files.writeString(readme, same);
    Files.setLastModifiedTime(readme, time);
    ProjectRefresh.Result r =
        ProjectRefresh.check(context.snapshot(), ws.index(), ws.reader(), ws.options());
    assertTrue(r.changes().isEmpty(), "size and time did not change");
    assertEquals(List.of("README.md"), r.changedSentFiles());
    assertTrue(r.isStale());
  }

  @Test
  void aSentFileThatIsGoneOrNoLongerTextIsStale() throws IOException {
    Files.delete(dir.resolve("README.md"));
    Files.write(dir.resolve("src/main/java/demo/App.java"), new byte[] {(byte) 0xff, (byte) 0xfe});
    ProjectRefresh.Result r =
        ProjectRefresh.check(context.snapshot(), ws.index(), ws.reader(), ws.options());
    assertEquals(List.of("README.md", "src/main/java/demo/App.java"), r.changedSentFiles());
  }

  @Test
  void refreshingKeepsTheWorkspaceInStepAndAChangeOfOptionsRescans() throws IOException {
    ProjectFixture.write(dir, "late.txt", "x");
    assertTrue(ws.index().find("late.txt") == null);
    ws.refresh(ws.options());
    assertTrue(ws.index().find("late.txt") != null);
    ProjectReader reader = ws.reader();
    reader.read(ws.index().find("README.md"), 100_000);
    assertTrue(reader.cachedFiles() > 0);
    ws.refresh(ws.options().withExclude(List.of("*.txt")));
    assertTrue(ws.index().find("late.txt") == null);
    assertEquals(0, reader.cachedFiles(), "new options start from a clean cache");
    assertEquals(dir.toRealPath(), ws.root());
  }

  @Test
  void theCacheIsReusedAcrossARefreshWhenFilesDidNotChange() throws IOException {
    ProjectFile readme = ws.index().find("README.md");
    ws.reader().read(readme, 100_000);
    int before = ws.reader().cachedFiles();
    ws.refresh(ws.options());
    assertEquals(before, ws.reader().cachedFiles());
  }
}
