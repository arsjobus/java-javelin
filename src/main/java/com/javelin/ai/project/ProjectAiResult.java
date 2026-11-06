package com.javelin.ai.project;

/**
 * The answer to a project request: the model's text, the proposal when the reply held file changes
 * (null otherwise), a note saying why there is none or what to be careful about (null when there is
 * nothing to say), and the snapshot of the context the answer was given for.
 */
public record ProjectAiResult(
    String text, ProjectProposal proposal, String note, ProjectSnapshot snapshot) {}
