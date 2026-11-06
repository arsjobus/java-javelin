package com.javelin.ai.project;

/**
 * A file that could have been in the context and is not. {@code why} is the kind of reason (so
 * "does not fit" can be told from "not relevant"), {@code reason} the sentence for the user, and
 * {@code tokens} what the file would have cost (0 when it could not be read).
 */
public record Omitted(ProjectFile file, Why why, String reason, int tokens) {
  public enum Why {
    /** It was wanted but the budget has no room for it. */
    DOES_NOT_FIT,
    /** Smart Context did not find it relevant. */
    NOT_SELECTED,
    /** The user took it out. */
    LEFT_OUT_BY_USER,
    /** It could not be read as text any more. */
    UNREADABLE
  }
}
