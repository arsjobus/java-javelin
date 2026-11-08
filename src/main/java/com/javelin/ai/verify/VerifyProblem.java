package com.javelin.ai.verify;

/**
 * One thing the compiler or a test said about a proposed change: a file, a line (0 if none), a
 * message.
 */
public record VerifyProblem(String path, int line, String message) {
  /** {@code Foo.java:12: cannot find symbol}, or just the message when there is no file. */
  public String label() {
    if (path == null || path.isEmpty()) return message;
    return path + (line > 0 ? ":" + line : "") + ": " + message;
  }
}
