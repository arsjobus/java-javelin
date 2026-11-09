package com.javelin.ai.plan;

import java.util.List;

/**
 * Marks project text as data in a prompt with begin and end lines that the text itself cannot fake.
 * Every marker carries a number, the smallest one that does not appear in any of the texts, so a
 * method that happens to contain a line like {@code ----- END x [1] -----} cannot end its block
 * early and make what follows look like the editor's own words.
 */
final class DataFence {
  private final int number;

  DataFence(List<String> texts) {
    int n = 1;
    while (clashes(texts, n)) n++;
    this.number = n;
  }

  private static boolean clashes(List<String> texts, int n) {
    String tail = "[" + n + "] -----";
    for (String t : texts) if (t.contains(tail)) return true;
    return false;
  }

  /**
   * The block for {@code content}, between a begin line and an end line that name {@code label}.
   */
  String block(String label, String content) {
    String body = content.isEmpty() || content.endsWith("\n") ? content : content + "\n";
    return "----- BEGIN "
        + label
        + " ["
        + number
        + "] -----\n"
        + body
        + "----- END "
        + label
        + " ["
        + number
        + "] -----\n";
  }
}
