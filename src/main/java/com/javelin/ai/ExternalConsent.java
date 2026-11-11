package com.javelin.ai;

import com.javelin.settings.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which projects may send code to an external provider. A grant names the provider, the destination
 * host and the project folder, so it covers nothing else: another project asks again, and so would
 * the same project if the destination ever changed. Grants live in {@code ai-consent.tsv} beside
 * the user settings file, never in a project, so opening a project cannot grant itself anything.
 * The file is read on every check: deleting a line (or the file) takes the consent back at once. If
 * a grant cannot be written, it holds for this session only.
 */
public final class ExternalConsent {
  private final Path file;
  private final Set<String> sessionOnly = ConcurrentHashMap.newKeySet();

  public ExternalConsent(Path file) {
    this.file = file;
  }

  public static ExternalConsent forSettings(Settings settings) {
    return new ExternalConsent(settings.userFile().resolveSibling("ai-consent.tsv"));
  }

  /** The file the grants are in, so a notice can say where to take one back. */
  public Path file() {
    return file;
  }

  public boolean granted(Path projectRoot, String providerId, String host) {
    String line = line(projectRoot, providerId, host);
    if (line == null) return false;
    return sessionOnly.contains(line) || stored().contains(line);
  }

  /**
   * Records consent for this project. True when it was written to the file; false when it holds for
   * this session only (or the project cannot be named, and nothing is granted).
   */
  public synchronized boolean grant(Path projectRoot, String providerId, String host) {
    String line = line(projectRoot, providerId, host);
    if (line == null) return false;
    Set<String> lines = new HashSet<>(stored());
    lines.add(line);
    if (write(lines)) {
      sessionOnly.remove(line);
      return true;
    }
    sessionOnly.add(line);
    return false;
  }

  public synchronized void revoke(Path projectRoot, String providerId, String host) {
    String line = line(projectRoot, providerId, host);
    if (line == null) return;
    sessionOnly.remove(line);
    Set<String> lines = new HashSet<>(stored());
    if (lines.remove(line)) write(lines);
  }

  public synchronized void revokeAll() {
    sessionOnly.clear();
    try {
      Files.deleteIfExists(file);
    } catch (IOException ex) {
      /* nothing was granted that can be read back, and nothing can be read from it either */
    }
  }

  private static String line(Path projectRoot, String providerId, String host) {
    if (projectRoot == null) return null;
    String root = real(projectRoot).toString();
    for (String part : List.of(providerId, host, root))
      if (part.isEmpty()
          || part.indexOf('\t') >= 0
          || part.indexOf('\n') >= 0
          || part.indexOf('\r') >= 0) return null;
    return providerId + "\t" + host + "\t" + root;
  }

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException ex) {
      return path.toAbsolutePath().normalize();
    }
  }

  private Set<String> stored() {
    Set<String> out = new HashSet<>();
    try {
      if (!Files.isRegularFile(file)) return out;
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
        if (!line.isBlank() && !line.startsWith("#")) out.add(line);
    } catch (IOException | RuntimeException ex) {
      out.clear();
    }
    return out;
  }

  private boolean write(Set<String> lines) {
    try {
      Path dir = file.toAbsolutePath().getParent();
      Files.createDirectories(dir);
      Path temp = Files.createTempFile(dir, "ai-consent", ".tmp");
      try {
        StringBuilder text =
            new StringBuilder(
                "# Projects allowed to send code to an external AI provider: provider, host,"
                    + " project folder. Delete a line to take the consent back.\n");
        lines.stream().sorted().forEach(l -> text.append(l).append('\n'));
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
          Files.move(
              temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
          Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temp);
      }
      return true;
    } catch (IOException | RuntimeException ex) {
      return false;
    }
  }
}
