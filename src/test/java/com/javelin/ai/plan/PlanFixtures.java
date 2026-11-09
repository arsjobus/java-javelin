package com.javelin.ai.plan;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.project.ScanOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A tiny project on disk, a scripted provider, and a way to prove nothing was written. */
final class PlanFixtures {
  private PlanFixtures() {}

  static final String CALC_PATH = "src/main/java/demo/Calc.java";
  static final String USER_PATH = "src/main/java/demo/User.java";

  static final String CALC =
      "package demo;\n\n"
          + "public class Calc {\n"
          + "  public int add(int a, int b) {\n"
          + "    return a - b;\n"
          + "  }\n\n"
          + "  public int twice(int a) {\n"
          + "    return a + a;\n"
          + "  }\n"
          + "}\n";
  static final String USER =
      "package demo;\n\n"
          + "import java.util.List;\n\n"
          + "public class User {\n"
          + "  int three() {\n"
          + "    return new Calc().add(1, 2);\n"
          + "  }\n"
          + "}\n";

  static Path project(Path dir) throws IOException {
    write(dir, CALC_PATH, CALC);
    write(dir, USER_PATH, USER);
    write(dir, "README.md", "# demo\n");
    return dir;
  }

  static void write(Path root, String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  static ProjectWorkspace workspace(Path dir) throws IOException {
    return new ProjectWorkspace(dir, ScanOptions.defaults());
  }

  /** A reply that changes one file with one edit block. */
  static String edit(String path, String search, String replace) {
    return "=== FILE: "
        + path
        + " ===\n<<<<<<< SEARCH\n"
        + search
        + "\n=======\n"
        + replace
        + "\n>>>>>>> REPLACE\n";
  }

  /** The reply that gives add a third parameter (its caller in User is then broken). */
  static final String ADD_WITH_THREE =
      edit(
          CALC_PATH,
          "  public int add(int a, int b) {\n    return a - b;",
          "  public int add(int a, int b, int c) {\n    return a + b + c;");

  /** The reply that updates User's call. */
  static final String USER_CALLS_THREE =
      edit(USER_PATH, "    return new Calc().add(1, 2);", "    return new Calc().add(1, 2, 0);");

  static TaskPlan plan(PlanStep... steps) {
    return new TaskPlan("give add a third parameter", List.of(steps));
  }

  static PlanStep step(String file, String symbol, String task) {
    return new PlanStep(file, symbol, false, task);
  }

  /** Every file under {@code root} with its content and modification time. */
  static Map<String, String> snapshot(Path root) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted().toList())
        out.put(
            root.relativize(p).toString(),
            Files.isRegularFile(p)
                ? Files.getLastModifiedTime(p).toMillis() + ":" + Files.readString(p)
                : "dir");
    }
    return out;
  }

  /** Answers from a script, one answer per request. */
  static final class Scripted implements AiProvider {
    final Deque<String> answers = new ArrayDeque<>();
    final List<AiRequest> requests = new ArrayList<>();
    boolean schema;
    boolean local = true;
    AiException failWith;
    int failAt = -1;

    Scripted script(String... texts) {
      answers.addAll(List.of(texts));
      return this;
    }

    @Override
    public String id() {
      return "scripted";
    }

    @Override
    public String displayName() {
      return "Scripted";
    }

    @Override
    public String location() {
      return "127.0.0.1:1";
    }

    @Override
    public boolean isLocal() {
      return local;
    }

    @Override
    public boolean supportsSchemaOutput() {
      return schema;
    }

    @Override
    public List<String> models(AiCancel cancel) {
      return List.of("m");
    }

    @Override
    public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
        throws AiException {
      requests.add(request);
      if (requests.size() == failAt) throw failWith;
      String answer = answers.isEmpty() ? "no more answers" : answers.removeFirst();
      onText.accept(answer);
      return new AiReply(answer, false);
    }
  }
}
