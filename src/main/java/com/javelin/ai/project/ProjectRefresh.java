package com.javelin.ai.project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps a project's picture current without starting over: looks at the project again (reusing what
 * is known about files that did not change) and says whether the files an earlier context sent are
 * still what the model saw. The check of the sent files is by content, so an edit that kept a
 * file's size and time is still noticed. JavaFX-free.
 */
public final class ProjectRefresh {
  private ProjectRefresh() {}

  /** The result of looking again: the new index and what changed since a snapshot. */
  public record Result(
      ProjectIndex index, ProjectSnapshot.Changes changes, List<String> changedSentFiles) {
    /** True when a file the context sent is no longer what the model saw. */
    public boolean isStale() {
      return !changedSentFiles.isEmpty();
    }
  }

  /**
   * Looks at the project again and compares it with {@code snapshot}. Files that were sent are
   * re-read (through {@code reader}) and fingerprinted; a file that is gone or whose text is
   * different is listed in {@code changedSentFiles}.
   */
  public static Result check(
      ProjectSnapshot snapshot, ProjectIndex previous, ProjectReader reader, ScanOptions options)
      throws IOException {
    ProjectIndex now = ProjectScanner.refresh(previous, options);
    ProjectSnapshot.Changes changes = snapshot.changesSince(now);
    List<String> changedSent = new ArrayList<>();
    for (Map.Entry<String, String> sent : snapshot.sentHashes().entrySet()) {
      try {
        String text = reader.readNow(sent.getKey(), options.maxFileBytes());
        if (!ProjectReader.hash(text).equals(sent.getValue())) changedSent.add(sent.getKey());
      } catch (IOException ex) {
        changedSent.add(sent.getKey()); // gone, or no longer text
      }
    }
    return new Result(now, changes, List.copyOf(changedSent));
  }
}
