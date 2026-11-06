package com.javelin.ai.project;

import java.util.List;

/**
 * Coordinated changes to several files that the model proposed, held until the user reviews them.
 * {@code snapshotId} names the context the model saw; {@code problems} are the parts of the reply
 * that could not be made into a change (a path outside the project, lines that are not in the
 * file...), each with the file, so nothing disappears silently. Nothing here touches a file:
 * applying is {@link ProposalApplier}, after approval.
 */
public record ProjectProposal(
    String snapshotId, String explanation, List<FileChange> changes, List<String> problems) {
  public ProjectProposal {
    changes = List.copyOf(changes);
    problems = List.copyOf(problems);
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
