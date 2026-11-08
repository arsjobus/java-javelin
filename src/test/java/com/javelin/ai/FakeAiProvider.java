package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A provider that answers from a script, for testing everything above the HTTP client. */
final class FakeAiProvider implements AiProvider {
  String answer = "";
  String thinking = "";
  boolean truncated;
  boolean schemaOutput;
  AiException failure;
  List<String> models = List.of("fake-model");
  final List<AiRequest> requests = new ArrayList<>();

  @Override
  public String id() {
    return "fake";
  }

  @Override
  public String displayName() {
    return "Fake";
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
  public boolean supportsSchemaOutput() {
    return schemaOutput;
  }

  @Override
  public List<String> models(AiCancel cancel) {
    return models;
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    requests.add(request);
    if (failure != null) throw failure;
    onText.accept(answer);
    return new AiReply(answer, truncated);
  }

  @Override
  public AiReply completeStreaming(
      AiRequest request, AiProvider.StreamListener listener, AiCancel cancel) throws AiException {
    requests.add(request);
    if (failure != null) throw failure;
    if (!thinking.isEmpty()) listener.onThinking(thinking);
    listener.onText(answer);
    return new AiReply(answer, truncated);
  }
}
