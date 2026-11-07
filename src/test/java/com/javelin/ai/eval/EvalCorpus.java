package com.javelin.ai.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** The fixture projects under {@code src/test/resources/eval}. */
final class EvalCorpus {
  private EvalCorpus() {}

  static final Path ROOT = Path.of("src", "test", "resources", "eval");

  /** Every task, sorted by id. */
  static List<EvalTask> load() throws IOException {
    return load(ROOT);
  }

  static List<EvalTask> load(Path root) throws IOException {
    List<Path> dirs;
    try (Stream<Path> list = Files.list(root)) {
      dirs = list.filter(Files::isDirectory).sorted().toList();
    }
    List<EvalTask> tasks = new java.util.ArrayList<>();
    for (Path dir : dirs) tasks.add(EvalTask.load(dir));
    return tasks;
  }
}
