package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.project.Adjustments;
import com.javelin.ai.project.ContextBudget;
import com.javelin.ai.project.ContextBuilder;
import com.javelin.ai.project.ContextMode;
import com.javelin.ai.project.Pick;
import com.javelin.ai.project.ProjectContext;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.project.ScanOptions;
import com.javelin.ai.project.SmartSelector;
import com.javelin.ai.project.SymbolOptions;
import com.javelin.ai.symbols.SymbolBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What symbol-level Smart Context does to the size of the context on the eval corpus, task by task,
 * with each profile. No model is involved: it is the text the editor would send for the task's
 * request, counted with the editor's own estimate. It says nothing about whether the model does
 * better or worse with that text; that is {@code mvn -Peval test -Deval.symbols=small}. The last
 * column is a diagnostic, not a profile. The table is written to {@code
 * target/eval/symbol-context-size.md}.
 */
class SymbolCorpusSizeTest {
  /**
   * The small profile with no whole-file threshold: every Java file with an exact outline becomes a
   * view however small it is. Not a profile anyone runs; it shows what the threshold is for.
   */
  private static final SymbolBudget SMALL_NO_THRESHOLD =
      new SymbolBudget("small, threshold 0", 3, 4_000, 6, 30, 6, 1_500, 4_000, 0);

  private static final List<SymbolBudget> COLUMNS =
      List.of(SymbolBudget.SMALL, SymbolBudget.FRONTIER, SMALL_NO_THRESHOLD);

  private static final ContextBudget BUDGET =
      new ContextBudget(
          0, ContextBudget.DEFAULT_RUNTIME_TOKENS, ContextBudget.DEFAULT_RESERVE_TOKENS);

  private static ProjectContext context(EvalTask task, SymbolOptions symbols) throws Exception {
    ProjectWorkspace ws = new ProjectWorkspace(task.before(), ScanOptions.defaults());
    List<Pick> picks = SmartSelector.select(ws.index(), ws.reader(), task.request(), ws.options());
    return ContextBuilder.build(
        ws.index(),
        ws.reader(),
        ContextMode.SMART,
        picks,
        Adjustments.NONE,
        BUDGET,
        100,
        ws.options(),
        1,
        symbols);
  }

  @Test
  void aProfileNeverMakesTheContextLargerOnTheCorpus() throws Exception {
    StringBuilder md = new StringBuilder();
    md.append("| Task | Files | Whole-file tokens |");
    for (SymbolBudget b : COLUMNS) md.append(" ").append(b.name()).append(" |");
    md.append("\n| --- | --- | --- |");
    for (int i = 0; i < COLUMNS.size(); i++) md.append(" --- |");
    md.append('\n');
    long files = 0;
    List<Long> symbolic = new ArrayList<>();
    for (int i = 0; i < COLUMNS.size(); i++) symbolic.add(0L);
    int tasks = 0;
    for (EvalTask task : EvalCorpus.load()) {
      if (task.mode() != EvalTask.Mode.PROJECT) continue;
      tasks++;
      ProjectContext whole = context(task, null);
      files += whole.textTokens();
      md.append("| ")
          .append(task.id())
          .append(" | ")
          .append(whole.included().size())
          .append(" | ")
          .append(whole.textTokens())
          .append(" |");
      for (int i = 0; i < COLUMNS.size(); i++) {
        SymbolBudget b = COLUMNS.get(i);
        ProjectContext c = context(task, new SymbolOptions(task.request(), b));
        if (b != SMALL_NO_THRESHOLD)
          assertTrue(
              c.textTokens() <= whole.textTokens(),
              task.id() + " " + b.name() + ": " + c.textTokens() + " vs " + whole.textTokens());
        symbolic.set(i, symbolic.get(i) + c.textTokens());
        md.append(' ').append(c.textTokens()).append(" |");
      }
      md.append('\n');
    }
    md.append("| **total** | | **").append(files).append("** |");
    for (long total : symbolic) md.append(" **").append(total).append("** |");
    md.append('\n');
    Path out = Path.of("target", "eval");
    Files.createDirectories(out);
    Files.writeString(out.resolve("symbol-context-size.md"), md.toString());
    System.out.println(md);
    assertTrue(tasks > 0);
    assertEquals(COLUMNS.size(), symbolic.size());
  }
}
