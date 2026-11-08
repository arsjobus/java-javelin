package com.javelin.ai;

/**
 * How an edit's lines were found in the code, from the strictest way to the loosest. The ladder is
 * tried in this order and the first rung that matches in exactly one place wins; the rung is kept
 * with the proposal so the review can say how much the model's copy of the code differed from the
 * real one.
 */
public enum AnchorRung {
  /** Every character matched. */
  EXACT("exact text"),
  /** Matched when spaces at the end of lines are ignored. */
  TRAILING_SPACE("ignoring spaces at the end of lines"),
  /** Matched when indentation is ignored. */
  INDENTATION("ignoring indentation"),
  /** Matched when every run of spaces and tabs counts as one space. */
  WHITESPACE("ignoring differences in spaces and tabs"),
  /**
   * The lines did not match, but the method they start with is declared once in the code, and the
   * edit is a whole method for a whole method: the method is replaced.
   */
  SIGNATURE("by the signature of the method");

  private final String label;

  AnchorRung(String label) {
    this.label = label;
  }

  /** Words for the review: "ignoring indentation". */
  public String label() {
    return label;
  }

  /** True for every rung but {@link #EXACT}: the model's lines were not a perfect copy. */
  public boolean isLoose() {
    return this != EXACT;
  }

  /** The rung of a {@link LineMatch} tier (0 exact ... 3 whitespace). */
  static AnchorRung ofTier(int tier) {
    return switch (tier) {
      case 0 -> EXACT;
      case 1 -> TRAILING_SPACE;
      case 2 -> INDENTATION;
      default -> WHITESPACE;
    };
  }
}
