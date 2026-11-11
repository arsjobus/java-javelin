package com.javelin.ai.project;

/**
 * One symbol of a file that is sent in part: what it is called, how it is shown ("in full",
 * "signature only" or "not shown") and why. The preview lists every one of them, so what the model
 * was shown of a file is never a guess.
 */
public record ShownSymbol(String name, String how, String why) {
  public static final String IN_FULL = "in full";
  public static final String SIGNATURE = "signature only";
  public static final String NOT_SHOWN = "not shown";

  public boolean isFull() {
    return IN_FULL.equals(how);
  }
}
