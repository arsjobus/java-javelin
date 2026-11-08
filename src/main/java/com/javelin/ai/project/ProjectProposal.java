package com.javelin.ai.project;

import com.javelin.ai.FailureReason;
import java.util.List;

/**
 * Coordinated changes to several files that the model proposed, held until the user reviews them.
 * {@code snapshotId} names the context the model saw; {@code problems} are the parts of the reply
 * that could not be made into a change (a path outside the project, lines that are not in the
 * file...), each with the file, so nothing disappears silently. Nothing here touches a file:
 * applying is {@link ProposalApplier}, after approval.
 */
public record ProjectProposal(
    String snapshotId,
    String explanation,
    List<FileChange> changes,
    List<String> problems,
    List<FailureReason> failures) {
  public ProjectProposal {
    changes = List.copyOf(changes);
    problems = List.copyOf(problems);
    failures = List.copyOf(failures);
  }

  /** A proposal whose problems have no recorded reason. */
  public ProjectProposal(
      String snapshotId, String explanation, List<FileChange> changes, List<String> problems) {
    this(snapshotId, explanation, changes, problems, List.of());
  }

  /**
   * The same proposal with {@code finding} added to the change for {@code path}, or to the first
   * change when no change has that path.
   */
  public ProjectProposal withFinding(String path, com.javelin.ai.Finding finding) {
    boolean named = changes.stream().anyMatch(c -> c.path().equals(path));
    List<FileChange> out = new java.util.ArrayList<>();
    boolean done = false;
    for (FileChange c : changes) {
      boolean hit = named ? c.path().equals(path) : !done;
      out.add(hit ? c.withFinding(finding) : c);
      done |= hit;
    }
    return new ProjectProposal(snapshotId, explanation, out, problems, failures);
  }

  public boolean isEmpty() {
    return changes.isEmpty();
  }

  public String summary() {
    int created = (int) changes.stream().filter(c -> c.kind() == FileChange.Kind.CREATE).count();
    return changes.size()
        + (changes.size() == 1 ? " file" : " files")
        + (created > 0 ? " (" + created + " new)" : "");
  }
}
