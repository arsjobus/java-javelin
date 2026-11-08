package com.javelin.ai.eval;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import com.javelin.ai.Sampling;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Wraps a provider for one task: asks for the run's sampling (temperature 0 and a seed), and counts
 * the calls and the tokens the provider reports. The pipeline under test is unchanged; it simply
 * talks to this instead of to the provider.
 */
final class MeteredProvider implements AiProvider {
  private final AiProvider inner;
  private final Sampling sampling;
  private int calls;
  private long promptTokens, completionTokens;
  private boolean allKnown = true;

  MeteredProvider(AiProvider inner, Sampling sampling) {
    this.inner = inner;
    this.sampling = sampling;
  }

  /** Model calls made so far (Phase 0 makes one per task; later phases retry). */
  int calls() {
    return calls;
  }

  /** Prompt tokens the provider reported, or -1 when a call did not say. */
  long promptTokens() {
    return allKnown && calls > 0 ? promptTokens : -1;
  }

  /** Completion tokens the provider reported, or -1 when a call did not say. */
  long completionTokens() {
    return allKnown && calls > 0 ? completionTokens : -1;
  }

  @Override
  public String id() {
    return inner.id();
  }

  @Override
  public String displayName() {
    return inner.displayName();
  }

  @Override
  public String location() {
    return inner.location();
  }

  @Override
  public boolean isLocal() {
    return inner.isLocal();
  }

  @Override
  public boolean supportsSchemaOutput() {
    return inner.supportsSchemaOutput();
  }

  @Override
  public List<String> models(AiCancel cancel) throws AiException {
    return inner.models(cancel);
  }

  @Override
  public OptionalInt contextLimit(String model, AiCancel cancel) throws AiException {
    return inner.contextLimit(model, cancel);
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    calls++;
    AiReply reply = inner.complete(request.withSampling(sampling), onText, cancel);
    if (reply.usage().isKnown()) {
      promptTokens += reply.usage().promptTokens();
      completionTokens += reply.usage().completionTokens();
    } else allKnown = false;
    return reply;
  }
}
