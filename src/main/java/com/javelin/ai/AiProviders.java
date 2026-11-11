package com.javelin.ai;

import java.util.List;

/**
 * The providers this version has. Choosing one is the user's: the {@code ai.provider} setting,
 * never a default that sends code anywhere but this computer. The only provider that is not on this
 * computer is {@code external}, and it also needs a per-project consent before it sends anything.
 */
public final class AiProviders {
  private AiProviders() {}

  public static List<String> available() {
    return List.of(OllamaProvider.ID, ExternalProvider.ID);
  }

  /** A provider that needs nothing but the settings: the external one is refused here. */
  public static AiProvider create(AiConfig config) throws AiException {
    return create(config, ExternalAccess.NONE);
  }

  /**
   * The provider the user's settings name. The external provider is created only when {@code
   * ai.provider} says {@code external}, a project is open (consent is per project) and a key can be
   * found; creating it sends nothing, and it still refuses every request until the project has
   * consented.
   */
  public static AiProvider create(AiConfig config, ExternalAccess access) throws AiException {
    if (config.provider().equals(OllamaProvider.ID))
      return new OllamaProvider(config.endpoint(), config.timeout(), config.thinkLevel());
    if (config.provider().equals(ExternalProvider.ID)) {
      if (access == null || !access.hasProject())
        throw new AiException(
            AiException.Kind.REFUSED,
            "The external AI provider needs an open project: the permission to send code to it is"
                + " given per project. Open a project, or set ai.provider to ollama.");
      String key = ExternalKey.read(access.config(), access.env(), access.projectRoot());
      return new ExternalProvider(
          new JdkExternalTransport(),
          key,
          config.timeout(),
          access.projectRoot(),
          access.consent(),
          access.config().structured());
    }
    throw new AiException(
        AiException.Kind.REFUSED,
        "'"
            + config.provider()
            + "' is not an AI provider this version has. Set ai.provider to one of: "
            + String.join(", ", available())
            + ".");
  }
}
