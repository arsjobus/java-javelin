package com.javelin.ai;

import java.util.List;

/**
 * An answer: the model's text, and for actions that propose a change the proposal if the reply held
 * a usable one. {@code note} says why there is no proposal (or what to be careful about), or is
 * null. {@code findings} are what the checks found wrong with the proposal ({@link ChangeChecker});
 * they are empty when there is no proposal. {@code failure} says, in the shared vocabulary, why a
 * change that was asked for produced no proposal; it is null when there is a proposal or when the
 * action does not propose anything.
 */
public record AiResult(
    String text, AiProposal proposal, String note, List<Finding> findings, FailureReason failure) {
  public AiResult {
    findings = List.copyOf(findings);
  }

  public AiResult(String text, AiProposal proposal, String note, List<Finding> findings) {
    this(text, proposal, note, findings, null);
  }

  public AiResult(String text, AiProposal proposal, String note) {
    this(text, proposal, note, List.of(), null);
  }

  /** The same result with one more finding, errors first. */
  public AiResult withFinding(Finding finding) {
    List<Finding> all = new java.util.ArrayList<>(findings);
    all.add(finding);
    all.sort((a, b) -> Boolean.compare(b.isError(), a.isError()));
    return new AiResult(text, proposal, note, all, failure);
  }

  /** True when a check found the proposal very likely broken. */
  public boolean hasErrors() {
    return findings.stream().anyMatch(Finding::isError);
  }
}
