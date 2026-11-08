package com.javelin.ai.verify;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Answers from a script, one answer per request; an entry that is an exception message fails. */
final class ScriptedProvider implements AiProvider {
  final Deque<String> answers = new ArrayDeque<>();
  final List<AiRequest> requests = new ArrayList<>();

  /** When set, the request with this number (1-based) fails with a timeout. */
  int failAt = -1;

  ScriptedProvider script(String... texts) {
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
    return true;
  }

  @Override
  public List<String> models(AiCancel cancel) {
    return List.of("m");
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    requests.add(request);
    if (requests.size() == failAt)
      throw new AiException(AiException.Kind.TIMEOUT, "the model did not answer");
    String answer = answers.isEmpty() ? "no more answers" : answers.removeFirst();
    onText.accept(answer);
    return new AiReply(answer, false);
  }
}
