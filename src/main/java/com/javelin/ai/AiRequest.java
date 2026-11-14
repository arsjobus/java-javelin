package com.javelin.ai;

/**
 * What is sent to a provider: a system message, the user's message and the model that should
 * answer. {@code contextTokens} is the context window to ask the provider for; 0 (the default) lets
 * the provider size it from the request, which is right for a selection and wrong for a whole
 * project, where the caller has already budgeted the window. {@code sampling} is how the model
 * should choose its words; the editor uses {@link Sampling#PRODUCT}. {@code responseSchema} is a
 * JSON Schema the answer must follow, for a provider that can be held to one ({@link
 * AiProvider#supportsSchemaOutput}); null (the default) leaves the answer free.
 */
public record AiRequest(
    String model,
    String system,
    String prompt,
    int contextTokens,
    Sampling sampling,
    String responseSchema) {
  public AiRequest {
    if (sampling == null) sampling = Sampling.PRODUCT;
    if (responseSchema != null && responseSchema.isBlank()) responseSchema = null;
  }

  public AiRequest(
      String model, String system, String prompt, int contextTokens, Sampling sampling) {
    this(model, system, prompt, contextTokens, sampling, null);
  }

  public AiRequest(String model, String system, String prompt, int contextTokens) {
    this(model, system, prompt, contextTokens, Sampling.PRODUCT);
  }

  public AiRequest(String model, String system, String prompt) {
    this(model, system, prompt, 0, Sampling.PRODUCT);
  }

  /** The same request with other sampling. */
  public AiRequest withSampling(Sampling other) {
    return new AiRequest(model, system, prompt, contextTokens, other, responseSchema);
  }

  /** The same request with an answer shape to follow (null for none). */
  public AiRequest withResponseSchema(String schema) {
    return new AiRequest(model, system, prompt, contextTokens, sampling, schema);
  }

  /** The same request asking the provider for a window of {@code tokens}. */
  public AiRequest withContextTokens(int tokens) {
    return new AiRequest(model, system, prompt, tokens, sampling, responseSchema);
  }

  /** The number of characters this request carries, which is what the context budget counts. */
  public int size() {
    return system.length() + prompt.length();
  }
}
