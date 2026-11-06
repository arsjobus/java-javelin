package com.javelin.ai.project;

/** Which files a project request sends. */
public enum ContextMode {
  /** Every eligible file that fits the budget, in a fixed order of importance. */
  FULL_PROJECT("Full Project"),
  /** The files that look relevant to the request, each with the reason. */
  SMART("Smart Context");

  private final String label;

  ContextMode(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
