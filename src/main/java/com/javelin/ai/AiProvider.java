package com.javelin.ai;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Where AI answers come from. The editor depends on this interface and never on a vendor: Ollama is
 * one implementation, another local server or an external service would be another. A provider is
 * only ever used after the user invoked an AI action and approved what is sent.
 */
public interface AiProvider {
  /** Receives model output as it arrives, keeping the final answer separate from its thinking. */
  @FunctionalInterface
  interface StreamListener {
    /** A piece of the model's answer. */
    void onText(String piece);

    /** A piece of the model's thinking, when the provider exposes it. */
    default void onThinking(String piece) {}
  }

  /** Short stable id, as written in the {@code ai.provider} setting. */
  String id();

  /** Name for people, for the provider indicator. */
  String displayName();

  /** Where requests go ("127.0.0.1:11434"), for the network indicator. */
  String location();

  /** True when requests never leave this computer. */
  boolean isLocal();

  /** The models the provider can run now, sorted. */
  List<String> models(AiCancel cancel) throws AiException;

  /**
   * True when the provider can be told the shape an answer must have ({@link
   * AiRequest#responseSchema}) and keeps to it. The default is false; a provider that says true
   * lets edits be asked for as JSON ({@link JsonEdits}). The pipeline reads this, never the
   * provider's name.
   */
  default boolean supportsSchemaOutput() {
    return false;
  }

  /**
   * True when a request's {@link AiRequest#contextTokens} decides how much room the model has for
   * its answer, so that asking again with a larger window can rescue an answer that was cut off.
   * The default is false: a provider with its own output limit gains nothing from a second try.
   */
  default boolean sizesContextWindow() {
    return false;
  }

  /**
   * True when the provider reports how many tokens a request used ({@link AiReply#usage}), so the
   * token estimate can be calibrated against it. The default is false.
   */
  default boolean reportsTokenCounts() {
    return false;
  }

  /**
   * What the person must be told before the first request of this provider in the open project,
   * when it needs their consent first; empty when no consent step is needed or it was already
   * given. A provider that returns text here refuses every request until {@link #recordConsent} was
   * called. The window shows the text and calls {@code recordConsent} only if the person agrees.
   */
  default java.util.Optional<String> consentNotice() {
    return java.util.Optional.empty();
  }

  /** Remembers that the person agreed to what {@link #consentNotice} said, for this project. */
  default void recordConsent() {}

  /**
   * The most tokens of context the model supports, when the provider can say. It is information for
   * the context budget; it carries no code. The default is "unknown".
   */
  default OptionalInt contextLimit(String model, AiCancel cancel) throws AiException {
    return OptionalInt.empty();
  }

  /**
   * Sends the request and waits for the whole answer, passing each piece of text to {@code onText}
   * as it arrives (from the calling thread). Stops with {@link AiException.Kind#CANCELLED} when
   * {@code cancel} is triggered.
   */
  AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel) throws AiException;

  /**
   * Sends the request and reports both answer and thinking output as it arrives. Providers that do
   * not expose thinking retain the ordinary text-only behavior.
   */
  default AiReply completeStreaming(AiRequest request, StreamListener listener, AiCancel cancel)
      throws AiException {
    return complete(request, listener::onText, cancel);
  }
}
