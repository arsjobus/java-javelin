package com.javelin.ai.project;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import com.javelin.ai.AiUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** A provider that answers from a script and knows a model limit, for the project tests. */
final class ScriptedProvider implements AiProvider {
  String answer = "";
  boolean truncated;
  AiUsage usage = AiUsage.UNKNOWN;
  boolean local = true;
  AiException failure;
  OptionalInt limit = OptionalInt.empty();
  int limitLookups;
  final List<AiRequest> requests = new ArrayList<>();

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
  public List<String> models(AiCancel cancel) {
    return List.of("m");
  }

  @Override
  public OptionalInt contextLimit(String model, AiCancel cancel) {
    limitLookups++;
    return limit;
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    requests.add(request);
    if (failure != null) throw failure;
    onText.accept(answer);
    return new AiReply(answer, truncated, usage);
  }
}
