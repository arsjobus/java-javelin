package com.javelin.ai;

/**
 * The things the user can ask for. Each one is a menu command; nothing runs unless it is chosen.
 */
public enum AiAction {
  EXPLAIN("Explain Selection", false, false),
  DEBUG("Debug Selection", false, false),
  GENERATE_TESTS("Generate Tests", true, false),
  PROPOSE_CHANGE("Propose Change", true, true);

  private final String label;
  private final boolean proposes;
  private final boolean needsInstruction;

  AiAction(String label, boolean proposes, boolean needsInstruction) {
    this.label = label;
    this.proposes = proposes;
    this.needsInstruction = needsInstruction;
  }

  public String label() {
    return label;
  }

  /**
   * True when the answer is a proposed change that the user reviews and applies or rejects; false
   * when it is text to read.
   */
  public boolean proposes() {
    return proposes;
  }

  /** True when the user has to say what should change. */
  public boolean needsInstruction() {
    return needsInstruction;
  }

  /** True when there must be selected text (Generate Tests may use the whole file instead). */
  public boolean needsSelection() {
    return this != GENERATE_TESTS;
  }
}
