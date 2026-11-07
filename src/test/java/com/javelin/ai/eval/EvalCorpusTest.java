package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The corpus is what every measurement stands on, so it is checked as strictly as code: every task
 * must fail before its reference change and pass after it (so a pass means the model's change did
 * the job), the reference change must be one the pipeline could express (so a failure is not the
 * task's fault), and the planted bugs must be caught by the reference tests.
 */
class EvalCorpusTest {
  @TempDir Path temp;

  static List<EvalTask> tasks() throws IOException {
    return EvalCorpus.load();
  }

  @Test
  void theCorpusHasTwentyToThirtyTasksOfEveryKind() throws IOException {
    List<EvalTask> tasks = tasks();
    assertTrue(tasks.size() >= 20 && tasks.size() <= 30, "tasks: " + tasks.size());
    assertEquals(
        EnumSet.allOf(EvalTask.Category.class),
        tasks.stream()
            .map(EvalTask::category)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(EvalTask.Category.class))));
    assertEquals(tasks.size(), tasks.stream().map(EvalTask::id).distinct().count());
  }

  @Test
  void theKindOfATaskDecidesWhichPartOfThePipelineItUses() throws IOException {
    for (EvalTask t : tasks()) {
      EvalTask.Mode expected =
          switch (t.category()) {
            case RENAME_ACROSS_FILES, CHANGE_SIGNATURE -> EvalTask.Mode.PROJECT;
            case ADD_TESTS -> EvalTask.Mode.GENERATE_TESTS;
            default -> EvalTask.Mode.SELECTION;
          };
      assertEquals(expected, t.mode(), t.id());
    }
  }

  @Test
  void aTaskWithoutTheFilesItNeedsIsRefused() throws IOException {
    Path dir = Files.createDirectories(temp.resolve("broken"));
    Files.writeString(
        dir.resolve("task.properties"), "id=other\ncategory=FIX_BUG\nmode=PROJECT\nrequest=x\n");
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> EvalTask.load(dir));
    Files.writeString(
        dir.resolve("task.properties"),
        "id=broken\ncategory=FIX_BUG\nmode=SELECTION\nrequest=x\ntests=a.B\n");
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> EvalTask.load(dir));
    Files.writeString(
        dir.resolve("task.properties"),
        "id=broken\ncategory=FIX_BUG\nmode=SELECTION\nrequest=x\nfile=A.java\n");
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> EvalTask.load(dir));
    Files.writeString(
        dir.resolve("task.properties"),
        "id=broken\ncategory=FIX_BUG\nmode=GENERATE_TESTS\nrequest=x\nfile=A.java\n");
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> EvalTask.load(dir));
    Files.writeString(
        dir.resolve("task.properties"), "id=broken\ncategory=FIX_BUG\nmode=PROJECT\ntests=a.B\n");
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> EvalTask.load(dir));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ids")
  void everyTaskFailsBeforeItsReferenceChangeAndPassesAfter(String id) throws IOException {
    EvalTask task = byId(id);
    if (task.mode() == EvalTask.Mode.GENERATE_TESTS) {
      generatedTestsTaskIsSound(task);
      return;
    }
    Path before = temp.resolve("before");
    Trees.copy(task.before(), before);
    FixtureBuild.Compiled compiledBefore = FixtureBuild.compile(before, temp.resolve("b"));
    boolean failsBefore =
        !compiledBefore.ok() || !FixtureBuild.run(compiledBefore.classes(), task.tests()).ok();
    assertTrue(failsBefore, id + ": the judging tests already pass without the change");

    Path after = temp.resolve("after");
    Trees.copy(task.before(), after);
    Trees.overlay(task.fix(), after);
    FixtureBuild.Compiled compiledAfter = FixtureBuild.compile(after, temp.resolve("a"));
    assertTrue(
        compiledAfter.ok(),
        id + ": the reference change does not compile: " + compiledAfter.errors());
    FixtureBuild.TestRun run = FixtureBuild.run(compiledAfter.classes(), task.tests());
    assertTrue(run.ok(), id + ": the reference change fails its tests: " + run.describe());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ids")
  void theReferenceChangeFitsWhatThePipelineAsksFor(String id) throws IOException {
    EvalTask task = byId(id);
    Map<String, String> before = Trees.read(task.before());
    Map<String, String> fix = Trees.read(task.fix());
    assertFalse(fix.isEmpty(), id + ": no reference change");
    assertTrue(task.maxChangedLines() >= EvalTask.MIN_CHANGED_LINES_LIMIT);
    if (task.mode() == EvalTask.Mode.SELECTION) {
      assertEquals(Set.of(task.file()), fix.keySet(), id + ": a selection task changes one file");
      String text = before.get(task.file());
      int[] range = Trees.selection(text, task.selectFrom(), task.selectTo());
      String after = fix.get(task.file());
      String prefix = text.substring(0, range[0]);
      String suffix = text.substring(range[1]);
      assertTrue(after.startsWith(prefix), id + ": the change reaches above the selection");
      assertTrue(
          after.length() >= prefix.length() + suffix.length() && after.endsWith(suffix),
          id + ": the change reaches below the selection");
      assertTrue(
          range[1] - range[0] < 6000,
          id + ": the selection is larger than a small model should be asked to rewrite");
    } else if (task.mode() == EvalTask.Mode.PROJECT) {
      assertTrue(fix.size() >= 2, id + ": a project task changes at least two files");
      for (String path : fix.keySet())
        assertTrue(before.containsKey(path), id + ": the reference change adds " + path);
    }
  }

  @Test
  void aSelectionIsFoundByItsFirstAndClosingLine() {
    String text = "class A {\n  void f() {\n    if (x) {\n    }\n  }\n  void g() {\n  }\n}\n";
    int[] range = Trees.selection(text, "void f() {", "}");
    assertEquals("  void f() {\n    if (x) {\n    }\n  }\n", text.substring(range[0], range[1]));
    int[] whole = Trees.selection(text, "class A {", "}");
    assertEquals(text, text.substring(whole[0], whole[1]));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Trees.selection(text, "void h() {", "}"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Trees.selection(text, "void g() {", "};"));
  }

  static Stream<String> ids() throws IOException {
    return tasks().stream().map(EvalTask::id);
  }

  private static EvalTask byId(String id) throws IOException {
    return tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
  }

  /**
   * For "generate tests" the corpus has no judging test; it has the source, a reference test and a
   * copy of the source with a bug planted in it.
   */
  private void generatedTestsTaskIsSound(EvalTask task) throws IOException {
    String id = task.id();
    assertTrue(
        Trees.read(task.before()).values().stream().noneMatch(s -> s.contains("@Test")),
        id + ": the source already has tests");
    Path real = temp.resolve("real");
    Trees.copy(task.before(), real);
    Trees.overlay(task.fix(), real);
    FixtureBuild.Compiled compiled = FixtureBuild.compile(real, temp.resolve("r"));
    assertTrue(compiled.ok(), id + ": " + compiled.errors());
    List<String> classes =
        Trees.read(task.fix()).keySet().stream()
            .map(
                p ->
                    p.replaceFirst("^src/test/java/", "")
                        .replace('/', '.')
                        .replaceFirst("\\.java$", ""))
            .toList();
    FixtureBuild.TestRun onReal = FixtureBuild.run(compiled.classes(), classes);
    assertTrue(
        onReal.ok(), id + ": the reference tests fail on the real code: " + onReal.describe());

    Map<String, String> mutantFiles = Trees.read(task.mutant());
    assertEquals(
        Set.of(task.file()), mutantFiles.keySet(), id + ": the mutant changes the source file");
    assertFalse(
        mutantFiles.get(task.file()).equals(Trees.read(task.before()).get(task.file())),
        id + ": the mutant is the same as the source");
    Path mutant = temp.resolve("mutant");
    Trees.copy(real, mutant);
    Trees.overlay(task.mutant(), mutant);
    FixtureBuild.Compiled mutantBuild = FixtureBuild.compile(mutant, temp.resolve("m"));
    assertTrue(mutantBuild.ok(), id + ": the mutant does not compile: " + mutantBuild.errors());
    FixtureBuild.TestRun onMutant = FixtureBuild.run(mutantBuild.classes(), classes);
    assertFalse(
        onMutant.failed().isEmpty(), id + ": the reference tests do not catch the planted bug");
  }
}
