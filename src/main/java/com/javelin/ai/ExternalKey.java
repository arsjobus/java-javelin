package com.javelin.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Finds the API key of the external provider. The editor is not a credential manager (PRIVACY.md):
 * the key is read from an environment variable, or from a file the person names that is outside the
 * open project, each time a provider is created. It is never written to settings, to a project, to
 * memory files or to a log, and it appears in no message this class produces.
 */
final class ExternalKey {
  private ExternalKey() {}

  static String read(ExternalConfig config, Function<String, String> env, Path projectRoot)
      throws AiException {
    String fromEnv = config.keyEnv().isEmpty() ? null : env.apply(config.keyEnv());
    if (fromEnv != null && !fromEnv.isBlank()) return checked(fromEnv.strip());
    if (!config.keyFile().isEmpty()) {
      Path file;
      try {
        file = Path.of(config.keyFile());
      } catch (InvalidPathException ex) {
        throw refused(ExternalConfig.KEY_FILE + " is not a valid path.");
      }
      if (!file.isAbsolute()) throw refused(ExternalConfig.KEY_FILE + " must be an absolute path.");
      if (projectRoot != null && inside(file, projectRoot))
        throw refused(
            "The key file is inside the open project. Keep it somewhere else: a key in a project"
                + " ends up in version control.");
      try {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String first = text.lines().findFirst().orElse("").strip();
        if (first.isEmpty()) throw refused("The key file is empty.");
        return checked(first);
      } catch (IOException ex) {
        throw refused("The key file could not be read.");
      }
    }
    throw refused(
        "No API key for the external provider. Set the environment variable "
            + config.keyEnv()
            + " before starting Javelin, or set "
            + ExternalConfig.KEY_FILE
            + " to a file outside your project that holds only the key. (An app started from the"
            + " Dock or Finder does not see variables from your shell profile; use the key file"
            + " there.) The key is never stored in settings or project files.");
  }

  private static String checked(String key) throws AiException {
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 33 || c > 126) throw refused("The API key contains characters a key cannot have.");
    }
    return key;
  }

  private static boolean inside(Path file, Path root) {
    return real(file).startsWith(real(root));
  }

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException ex) {
      return path.toAbsolutePath().normalize();
    }
  }

  private static AiException refused(String message) {
    return new AiException(AiException.Kind.REFUSED, message);
  }
}
