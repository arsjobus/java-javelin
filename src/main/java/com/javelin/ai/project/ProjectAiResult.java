package com.javelin.ai.project;

import com.javelin.ai.FailureReason;

/**
 * The answer to a project request: the model's text, the proposal when the reply held file changes
 * (null otherwise), a note saying why there is none or what to be careful about (null when there is
 * nothing to say), the snapshot of the context the answer was given for, and, when a change was
 * asked for and none came out, the shared reason why ({@link FailureReason}; null otherwise).
 */
public record ProjectAiResult(
    String text,
    ProjectProposal proposal,
    String note,
    ProjectSnapshot snapshot,
    FailureReason failure) {
  public ProjectAiResult(
      String text, ProjectProposal proposal, String note, ProjectSnapshot snapshot) {
    this(text, proposal, note, snapshot, null);
  }
}
