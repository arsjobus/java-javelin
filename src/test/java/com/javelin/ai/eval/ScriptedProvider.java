package com.javelin.ai.eval;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import com.javelin.ai.AiUsage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A provider that answers from a function of the request, for testing the eval without a model.
 * {@link #oracle} answers every task with its reference change, written in the shape the pipeline
 * asks for, which proves the corpus and the runner agree end to end.
 */
final class ScriptedProvider implements AiProvider {
  final List<AiRequest> requests = new ArrayList<>();
  private final Function<AiRequest, AiReply> script;
  AiException failure;

  ScriptedProvider(Function<AiRequest, AiReply> script) {
    this.script = script;
  }

  static ScriptedProvider replying(String text) {
    return new ScriptedProvider(r -> new AiReply(text, false, new AiUsage(100, 20)));
  }

  /** Answers each task with its reference change. */
  static ScriptedProvider oracle(List<EvalTask> tasks) {
    return new ScriptedProvider(
        request -> {
          for (EvalTask t : tasks)
            if (t.mode() == EvalTask.Mode.GENERATE_TESTS
                ? request.prompt().contains("File: " + Path.of(t.file()).getFileName() + " ")
                : request.prompt().contains(t.request())) {
              try {
                return new AiReply(referenceReply(t), false, new AiUsage(500, 50));
              } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
              }
            }
          throw new IllegalStateException("no task for " + request.prompt());
        });
  }

  private static String referenceReply(EvalTask t) throws IOException {
    Map<String, String> before = Trees.read(t.before());
    Map<String, String> fix = Trees.read(t.fix());
    switch (t.mode()) {
      case SELECTION:
        {
          String text = before.get(t.file());
          int[] range = Trees.selection(text, t.selectFrom(), t.selectTo());
          String after = fix.get(t.file());
          String replacement =
              after.substring(range[0], after.length() - (text.length() - range[1]));
          return "Here is the change.\n\n```java\n" + replacement.stripTrailing() + "\n```\n";
        }
      case GENERATE_TESTS:
        return "```java\n" + fix.values().iterator().next() + "```\n";
      default:
        {
          StringBuilder reply = new StringBuilder("The change:\n\n");
          for (Map.Entry<String, String> e : fix.entrySet())
            reply
                .append("=== FILE: ")
                .append(e.getKey())
                .append(" ===\n```java\n")
                .append(e.getValue())
                .append("```\n\n");
          return reply.toString();
        }
    }
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
    return true;
  }

  @Override
  public List<String> models(AiCancel cancel) {
    return List.of("scripted-model");
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    requests.add(request);
    if (failure != null) throw failure;
    AiReply reply = script.apply(request);
    onText.accept(reply.text());
    return reply;
  }

  static String read(java.nio.file.Path path) throws IOException {
    return Files.readString(path);
  }
}
