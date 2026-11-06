package com.javelin.ai.project;

import java.util.regex.Pattern;

/** The little a multi-file reply needs to know about diffs that create a file. */
final class UnifiedPatchHelper {
  private UnifiedPatchHelper() {}

  private static final Pattern HUNK =
      Pattern.compile("(?m)^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@.*$");

  static boolean hasHunk(String text) {
    return HUNK.matcher(text).find();
  }

  /** True for a diff whose old side is {@code /dev/null}. */
  static boolean isCreationDiff(String body) {
    return body.lines().anyMatch(l -> l.startsWith("--- /dev/null"));
  }

  /** The lines a creation diff adds, as the text of the new file. */
  static String addedText(String body) {
    StringBuilder out = new StringBuilder();
    boolean inHunk = false;
    for (String line : body.split("\\R", -1)) {
      if (line.startsWith("@@")) {
        inHunk = true;
        continue;
      }
      if (inHunk && line.startsWith("+")) out.append(line.substring(1)).append('\n');
    }
    return out.toString();
  }
}
