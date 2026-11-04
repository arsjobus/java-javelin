package com.javelin.build;

import java.util.regex.Pattern;

/** Removes ANSI escape sequences, since the output panels render plain text. */
public final class Ansi {
  // CSI (colours, cursor movement), OSC (titles, hyperlinks; ended by BEL or ESC \), charset
  // selection (ESC ( B), and
  // two-character escapes such as ESC = and ESC >. An unterminated OSC removes only its own
  // introducer, never later text.
  private static final Pattern ESCAPES =
      Pattern.compile(
          "\u001B\\[[0-9;?]*[ -/]*[@-~]"
              + "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"
              + "|\u001B[()*+][0-9A-Za-z]"
              + "|\u001B[=>78cDEHMNO\\\\]");

  private Ansi() {}

  public static String strip(String text) {
    return ESCAPES.matcher(text).replaceAll("");
  }
}
