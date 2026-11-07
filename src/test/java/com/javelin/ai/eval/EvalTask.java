package com.javelin.ai.eval;

import com.javelin.ai.LineDiff;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * One piece of work for the model, as a small project on disk:
 *
 * <pre>
 * eval/&lt;id&gt;/task.properties   what is asked, where, and which tests judge it
 * eval/&lt;id&gt;/before/           the project as the model finds it (sources and the judging test)
 * eval/&lt;id&gt;/fix/              the reference change: files a correct change replaces or adds
 * eval/&lt;id&gt;/mutant/           only for ADD_TESTS: the same source with a planted bug
 * </pre>
 *
 * The judging test fails on {@code before} and passes on {@code before + fix}; {@code
 * EvalCorpusTest} checks that for every task. For ADD_TESTS the model's own tests are judged
 * instead: they must pass on the real code and fail on the mutant.
 */
record EvalTask(
    String id,
    Category category,
    Mode mode,
    String request,
    String file,
    String selectFrom,
    String selectTo,
    List<String> tests,
    int maxChangedLines,
    Path dir) {

  enum Category {
    ADD_METHOD,
    FIX_BUG,
    EXTRACT_METHOD,
    RENAME_ACROSS_FILES,
    CHANGE_SIGNATURE,
    ADD_TESTS
  }

  /** Which part of the AI workflow the task goes through. */
  enum Mode {
    /** Propose Change on a selection of one file. */
    SELECTION,
    /** A project request: the model works out which files change. */
    PROJECT,
    /** Generate Tests for one file. */
    GENERATE_TESTS
  }

  /** A rewrite is never allowed to be smaller than this many changed lines. */
  static final int MIN_CHANGED_LINES_LIMIT = 40;

  /** How many times the reference change a proposal may be before it counts as oversized. */
  static final int CHANGED_LINES_FACTOR = 4;

  Path before() {
    return dir.resolve("before");
  }

  Path fix() {
    return dir.resolve("fix");
  }

  Path mutant() {
    return dir.resolve("mutant");
  }

  static EvalTask load(Path dir) throws IOException {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(dir.resolve("task.properties"))) {
      p.load(in);
    }
    String id = dir.getFileName().toString();
    if (!id.equals(p.getProperty("id")))
      throw new IOException(dir + ": id must be the folder name, '" + id + "'");
    Category category = Category.valueOf(required(p, "category", id));
    Mode mode = Mode.valueOf(required(p, "mode", id));
    String file = p.getProperty("file", "");
    String from = p.getProperty("select.from", "");
    String to = p.getProperty("select.to", "");
    List<String> tests =
        Arrays.stream(p.getProperty("tests", "").split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toList();
    if (mode != Mode.PROJECT && file.isEmpty())
      throw new IOException(id + ": " + mode + " needs a 'file'");
    if (mode == Mode.SELECTION && (from.isEmpty() || to.isEmpty()))
      throw new IOException(id + ": SELECTION needs select.from and select.to");
    if (mode == Mode.GENERATE_TESTS) {
      if (!Files.isDirectory(dir.resolve("mutant")))
        throw new IOException(id + ": GENERATE_TESTS needs a mutant/ folder");
    } else if (tests.isEmpty()) throw new IOException(id + ": 'tests' is empty");
    int referenceLines = changedLines(dir.resolve("before"), dir.resolve("fix"));
    int limit =
        Integer.parseInt(
            p.getProperty(
                "maxChangedLines",
                String.valueOf(
                    Math.max(MIN_CHANGED_LINES_LIMIT, CHANGED_LINES_FACTOR * referenceLines))));
    return new EvalTask(
        id, category, mode, required(p, "request", id), file, from, to, tests, limit, dir);
  }

  private static String required(Properties p, String key, String id) throws IOException {
    String value = p.getProperty(key);
    if (value == null || value.isBlank()) throw new IOException(id + ": missing '" + key + "'");
    return value.strip();
  }

  /** Lines added plus removed by the files of {@code fix} against {@code before}. */
  static int changedLines(Path before, Path fix) throws IOException {
    int total = 0;
    Map<String, String> after = Trees.read(fix);
    Map<String, String> base = Trees.read(before);
    for (Map.Entry<String, String> e : after.entrySet()) {
      LineDiff.Result diff = LineDiff.unified(base.getOrDefault(e.getKey(), ""), e.getValue(), 0);
      total += diff.added() + diff.removed();
    }
    return total;
  }
}
