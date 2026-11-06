package com.javelin.ai.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a scan found: the files that may be AI context, in path order, and everything that was left
 * out with the reason, so the user can see what the model will never be shown. {@code excluded} is
 * capped; {@code excludedTotal} is the real count.
 */
public record ProjectIndex(
    Path root,
    List<ProjectFile> files,
    List<Excluded> excluded,
    int excludedTotal,
    boolean limited) {
  /** Why something was left out. */
  public enum Reason {
    GITIGNORE("ignored by .gitignore"),
    GENERATED("generated, build output or a cache"),
    BINARY("binary file"),
    SENSITIVE("may hold credentials"),
    TOO_LARGE("larger than the file size limit"),
    SYMLINK("symbolic link (never followed)"),
    EXCLUDED("excluded by ai.project.exclude"),
    UNREADABLE("cannot be read"),
    LIMIT("beyond the file count limit");

    private final String label;

    Reason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** A path (a folder ends with {@code /}) that was not taken, and why. */
  public record Excluded(String path, Reason reason) {}

  public ProjectIndex {
    files = List.copyOf(files);
    excluded = List.copyOf(excluded);
  }

  /** The project's name: its folder. */
  public String name() {
    Path last = root.getFileName();
    return last == null ? root.toString() : last.toString();
  }

  public ProjectFile find(String path) {
    for (ProjectFile file : files) if (file.path().equals(path)) return file;
    return null;
  }

  /** How many files per language, for the overview. */
  public Map<String, Long> languageCounts() {
    return files.stream()
        .filter(f -> f.language() != null)
        .collect(
            Collectors.groupingBy(
                ProjectFile::language, java.util.TreeMap::new, Collectors.counting()));
  }

  /** How many things each reason left out (of the ones listed). */
  public Map<Reason, Long> excludedByReason() {
    return excluded.stream()
        .collect(
            Collectors.groupingBy(Excluded::reason, java.util.TreeMap::new, Collectors.counting()));
  }
}
