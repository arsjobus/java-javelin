package com.javelin.ai.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What an apply wrote, so it can be taken back. {@code previous} is the text a file had before
 * (null for a file the apply created), {@code written} what was put there. Rolling back restores a
 * file only if it still holds exactly what was written: a file edited since is left alone and
 * reported, so undoing the AI never undoes the user's own work.
 */
public final class AppliedChanges {
  record Entry(String path, String previous, String written, List<Path> createdFolders) {}

  /** The outcome of a rollback. */
  public record Rollback(List<String> restored, List<String> skipped) {}

  private final List<Entry> entries;

  private AppliedChanges(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  static AppliedChanges of(List<Entry> entries) {
    return new AppliedChanges(entries);
  }

  public List<String> paths() {
    return entries.stream().map(Entry::path).toList();
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  public Rollback rollback(ProjectReader reader) {
    List<String> restored = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry e = entries.get(i);
      try {
        Path file = reader.resolve(e.path());
        String now =
            Files.exists(file) ? com.javelin.io.FileIO.readText(file, Long.MAX_VALUE) : null;
        if (now == null || !ProjectReader.hash(now).equals(ProjectReader.hash(e.written()))) {
          skipped.add(e.path() + " (it changed since, so it was left as it is)");
          continue;
        }
        if (e.previous() == null) {
          Files.delete(file);
          for (Path folder : e.createdFolders()) deleteIfEmpty(folder);
        } else {
          com.javelin.io.FileIO.writeAtomic(file, e.previous());
        }
        restored.add(e.path());
      } catch (IOException ex) {
        skipped.add(e.path() + " (" + ex.getMessage() + ")");
      }
    }
    return new Rollback(restored, skipped);
  }

  private static void deleteIfEmpty(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) return;
    try (Stream<Path> list = Files.list(folder)) {
      if (list.findAny().isEmpty()) Files.delete(folder);
    }
  }
}
