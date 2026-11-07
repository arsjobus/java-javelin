package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiProviders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The live measurement. It talks to a model, so it is excluded from {@code mvn test} and runs only
 * under the {@code eval} profile:
 *
 * <pre>
 * mvn -Peval test -Deval.model=qwen3.5:9b
 * </pre>
 *
 * Options (all optional except the model): {@code -Deval.runs=3}, {@code -Deval.seed=42}, {@code
 * -Deval.tasks=<regex on task ids>}, {@code -Deval.endpoint=http://127.0.0.1:11434}, {@code
 * -Deval.think=none|low|medium|high}, {@code -Deval.timeoutSeconds=120}. It writes {@code
 * target/eval/results-<date>.json} and {@code target/eval/baseline-<date>.md}; commit the Markdown
 * as {@code docs/eval/baseline-<date>.md}. The model is reached only through the editor's
 * loopback-only provider, so this cannot send the corpus anywhere but this computer.
 */
@Tag("eval")
class EvalRun {
  @Test
  void measureTheModel() throws Exception {
    EvalProfile profile = EvalProfile.resolve(System::getProperty);
    int runs = Integer.parseInt(System.getProperty("eval.runs", "3"));
    long seed = Long.parseLong(System.getProperty("eval.seed", "42"));
    Pattern filter = Pattern.compile(System.getProperty("eval.tasks", ".*"));
    List<EvalTask> tasks =
        EvalCorpus.load().stream().filter(t -> filter.matcher(t.id()).find()).toList();
    assertFalse(tasks.isEmpty(), "no task matches -Deval.tasks");

    AiProvider provider = AiProviders.create(profile.aiConfig());
    try {
      if (!provider.models(new AiCancel()).contains(profile.model()))
        throw new IllegalStateException(
            "Ollama does not have '" + profile.model() + "'. Run: ollama pull " + profile.model());
    } catch (AiException ex) {
      throw new IllegalStateException("Cannot measure: " + ex.getMessage(), ex);
    }

    Path scratch = Files.createTempDirectory("javelin-eval-");
    EvalRunner runner = new EvalRunner(provider, profile, scratch);
    long started = System.nanoTime();
    List<List<TaskResult>> all = new ArrayList<>();
    for (int run = 0; run < runs; run++)
      all.add(runner.runAll(tasks, seed, run, line -> System.out.println("[eval] " + line)));
    String date = LocalDate.now().toString();
    EvalReport report =
        new EvalReport(
            profile.name(),
            profile.model(),
            date,
            seed,
            tasks.size(),
            all,
            (System.nanoTime() - started) / 1_000_000);
    Path out = Path.of("target", "eval");
    Files.createDirectories(out);
    Files.writeString(out.resolve("results-" + date + ".json"), report.toJson());
    Files.writeString(out.resolve("baseline-" + date + ".md"), report.toMarkdown());
    System.out.println("[eval] " + Files.readString(out.resolve("baseline-" + date + ".md")));
  }
}
