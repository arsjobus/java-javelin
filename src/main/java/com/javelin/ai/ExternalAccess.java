package com.javelin.ai;

import com.javelin.settings.Settings;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * What {@link AiProviders} needs to create the external provider: the open project (consent is per
 * project), the consent store, where the key comes from, and the environment to look in. A request
 * made without a project has no access, so the external provider is refused there.
 */
public record ExternalAccess(
    Path projectRoot,
    ExternalConsent consent,
    ExternalConfig config,
    Function<String, String> env) {
  /** No project, no consent store: only local providers can be created. */
  public static final ExternalAccess NONE = new ExternalAccess(null, null, null, null);

  public static ExternalAccess from(Settings settings, Path projectRoot) {
    return new ExternalAccess(
        projectRoot,
        ExternalConsent.forSettings(settings),
        ExternalConfig.from(settings),
        System::getenv);
  }

  public boolean hasProject() {
    return projectRoot != null && consent != null && config != null && env != null;
  }
}
