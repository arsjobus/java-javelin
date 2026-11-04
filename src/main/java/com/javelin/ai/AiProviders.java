package com.javelin.ai;

import java.util.List;

/**
 * The providers this version has. Choosing one is the user's: the {@code ai.provider} setting,
 * never a default that sends code anywhere but this computer.
 */
public final class AiProviders {
  private AiProviders() {}

  public static List<String> available() {
    return List.of(OllamaProvider.ID);
  }

  public static AiProvider create(AiConfig config) throws AiException {
    if (config.provider().equals(OllamaProvider.ID))
      return new OllamaProvider(config.endpoint(), config.timeout());
    throw new AiException(
        AiException.Kind.REFUSED,
        "'"
            + config.provider()
            + "' is not an AI provider this version has. Set ai.provider to one of: "
            + String.join(", ", available())
            + ".");
  }
}
