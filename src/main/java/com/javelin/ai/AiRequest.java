package com.javelin.ai;

/**
 * What is sent to a provider: a system message, the user's message and the model that should
 * answer.
 */
public record AiRequest(String model, String system, String prompt) {
  /** The number of characters this request carries, which is what the context budget counts. */
  public int size() {
    return system.length() + prompt.length();
  }
}
