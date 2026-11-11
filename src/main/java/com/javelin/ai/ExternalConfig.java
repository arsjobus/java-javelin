package com.javelin.ai;

import com.javelin.settings.Settings;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code ai.external.*} settings: where the API key comes from, never the key itself, and
 * whether the provider may be asked for JSON edits. User settings file only, like every {@code
 * ai.*} key. There is deliberately no setting for the destination: the host is fixed in the code.
 */
public record ExternalConfig(String keyEnv, String keyFile, boolean structured) {
  public static final String KEY_ENV = "ai.external.keyEnv";
  public static final String KEY_FILE = "ai.external.keyFile";
  public static final String STRUCTURED = "ai.external.structured";

  public static final String DEFAULT_KEY_ENV = "ANTHROPIC_API_KEY";

  private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  public static ExternalConfig from(Settings settings) {
    return new ExternalConfig(
        settings.get(KEY_ENV, DEFAULT_KEY_ENV).strip(),
        settings.get(KEY_FILE, "").strip(),
        settings.getBoolean(STRUCTURED, false));
  }

  public static List<String> validate(Settings settings) {
    List<String> problems = new ArrayList<>();
    String env = settings.get(KEY_ENV, null);
    if (env != null && !ENV_NAME.matcher(env.strip()).matches())
      problems.add(KEY_ENV + ": '" + env + "' is not the name of an environment variable");
    String file = settings.get(KEY_FILE, null);
    if (file != null && !file.isBlank()) {
      try {
        if (!Path.of(file.strip()).isAbsolute())
          problems.add(KEY_FILE + ": '" + file + "' must be an absolute path");
      } catch (InvalidPathException ex) {
        problems.add(KEY_FILE + ": '" + file + "' is not a valid path");
      }
    }
    String structured = settings.get(STRUCTURED, null);
    if (structured != null
        && !structured.strip().equalsIgnoreCase("true")
        && !structured.strip().equalsIgnoreCase("false"))
      problems.add(STRUCTURED + ": '" + structured + "' is not true or false; false is used");
    return problems;
  }
}
