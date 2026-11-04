package com.javelin.ai;

import java.util.List;

/**
 * An answer: the model's text, and for actions that propose a change the proposal if the reply held
 * a usable one. {@code note} says why there is no proposal (or what to be careful about), or is
 * null. {@code findings} are what the checks found wrong with the proposal ({@link ChangeChecker});
 * they are empty when there is no proposal.
 */
public record AiResult(String text, AiProposal proposal, String note, List<Finding> findings) {
  public AiResult {
    findings = List.copyOf(findings);
  }

  public AiResult(String text, AiProposal proposal, String note) {
    this(text, proposal, note, List.of());
  }

  /** True when a check found the proposal very likely broken. */
  public boolean hasErrors() {
    return findings.stream().anyMatch(Finding::isError);
  }
}
