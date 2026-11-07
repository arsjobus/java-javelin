package text;

public final class TextStats {
  private TextStats() {}

  public static int lineCount(String text) {
    if (text.isEmpty()) return 0;
    return text.split("\n", -1).length;
  }

  public static int longestLine(String text) {
    int longest = 0;
    for (String line : text.split("\n", -1)) longest = Math.max(longest, line.length());
    return longest;
  }
}
