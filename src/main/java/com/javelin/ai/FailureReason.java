package com.javelin.ai;

/**
 * Why an AI change did not work, in one shared vocabulary. The editor's checks, the eval harness
 * and the roadmap's later phases all use these names, so a failure counted in one place means the
 * same in another. Each is something a model did (or a limit it hit), not something the machine
 * did: a provider that is not running is not a failure of the model and has no reason.
 */
public enum FailureReason {
  /** The reply holds nothing to apply: no edit block, no code block, no file sections. */
  NO_EDIT_BLOCK("The reply holds no change that can be applied."),
  /** The reply has the shape of an edit but it cannot be read (an empty SEARCH part, no hunks). */
  UNPARSEABLE("The reply could not be read as an edit."),
  /** An edit says which lines it changes and they are not in the code, or are in several places. */
  ANCHOR_NOT_FOUND("An edit's lines are not in the code, or fit more than one place."),
  /** An edit names a file that is not in the project or that the AI may not change. */
  WRONG_FILE("An edit names a file that does not exist or may not be changed."),
  /** The request or the answer is larger than the limits allow. */
  OVERSIZED("The request or the change is larger than the limits allow."),
  /** The model stopped because it ran out of room, in the middle of its answer. */
  TRUNCATED("The reply was cut off before it was finished."),
  /** The change was applied and the code no longer compiles. */
  COMPILE_ERROR("The changed code does not compile."),
  /** The change was applied, the code compiles and the tests still fail. */
  TEST_FAILURE("The changed code compiles but its tests fail."),
  /** The request was refused before or instead of an answer (no model, a credentials file...). */
  REFUSED("The request was refused."),
  /**
   * The provider stopped answering in time. Not in the roadmap's first list; added because a small
   * model that thinks for minutes is a real way for it to fail, and it must not be counted as
   * something else.
   */
  TIMEOUT("The model did not answer in time.");

  private final String description;

  FailureReason(String description) {
    this.description = description;
  }

  /** One sentence for reports. */
  public String description() {
    return description;
  }
}
