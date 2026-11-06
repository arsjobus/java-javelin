package com.javelin.ai;

/**
 * What is sent to a provider: a system message, the user's message and the model that should
 * answer. {@code contextTokens} is the context window to ask the provider for; 0 (the default) lets
 * the provider size it from the request, which is right for a selection and wrong for a whole
 * project, where the caller has already budgeted the window.
 */
public record AiRequest(String model, String system, String prompt, int contextTokens) {
  public AiRequest(String model, String system, String prompt) {
    this(model, system, prompt, 0);
  }

  /** The number of characters this request carries, which is what the context budget counts. */
  public int size() {
    return system.length() + prompt.length();
  }
}
