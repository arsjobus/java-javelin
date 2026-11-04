package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff of two texts by lines, for showing a proposed change before it is applied.
 * JavaFX-free.
 */
public final class LineDiff {
  private LineDiff() {}

  /**
   * The diff as text ({@code -} removed, {@code +} added, a space for context), and how many lines
   * were added and removed.
   */
  public record Result(String text, int added, int removed, boolean changed) {}

  /**
   * Above this many line pairs the lines in between are shown as removed and added, as a whole,
   * instead of being matched.
   */
  static final int MAX_MATCHED_PAIRS = 2_000_000;

  private enum Type {
    SAME,
    DELETE,
    INSERT
  }

  private record Op(Type type, String line) {}

  public static Result unified(String before, String after, int context) {
    String a = before.replace("\r\n", "\n");
    String b = after.replace("\r\n", "\n");
    if (a.equals(b))
      return new Result(
          before.equals(after) ? "No changes." : "No lines differ; only the line endings changed.",
          0,
          0,
          !before.equals(after));
    List<Op> ops = ops(lines(a), lines(b));
    int added = 0, removed = 0;
    for (Op op : ops) {
      if (op.type == Type.INSERT) added++;
      else if (op.type == Type.DELETE) removed++;
    }
    if (added == 0 && removed == 0)
      return new Result("No lines differ; only the final line break changed.", 0, 0, true);
    return new Result(hunks(ops, Math.max(0, context)), added, removed, true);
  }

  static List<String> lines(String text) {
    if (text.isEmpty()) return new ArrayList<>();
    List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
    if (text.endsWith("\n")) lines.remove(lines.size() - 1);
    return lines;
  }

  private static List<Op> ops(List<String> a, List<String> b) {
    int prefix = 0;
    while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
    int suffix = 0;
    while (suffix < a.size() - prefix
        && suffix < b.size() - prefix
        && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
    List<String> x = a.subList(prefix, a.size() - suffix);
    List<String> y = b.subList(prefix, b.size() - suffix);
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < prefix; i++) ops.add(new Op(Type.SAME, a.get(i)));
    int n = x.size(), m = y.size();
    if ((long) n * m > MAX_MATCHED_PAIRS) {
      for (String line : x) ops.add(new Op(Type.DELETE, line));
      for (String line : y) ops.add(new Op(Type.INSERT, line));
    } else {
      int[] lcs = new int[(n + 1) * (m + 1)]; // lcs[i][j]: longest common run of x[i..] and y[j..]
      int w = m + 1;
      for (int i = n - 1; i >= 0; i--)
        for (int j = m - 1; j >= 0; j--)
          lcs[i * w + j] =
              x.get(i).equals(y.get(j))
                  ? lcs[(i + 1) * w + j + 1] + 1
                  : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
      int i = 0, j = 0;
      while (i < n && j < m) {
        if (x.get(i).equals(y.get(j))) {
          ops.add(new Op(Type.SAME, x.get(i)));
          i++;
          j++;
        } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1])
          ops.add(new Op(Type.DELETE, x.get(i++)));
        else ops.add(new Op(Type.INSERT, y.get(j++)));
      }
      while (i < n) ops.add(new Op(Type.DELETE, x.get(i++)));
      while (j < m) ops.add(new Op(Type.INSERT, y.get(j++)));
    }
    for (int i = a.size() - suffix; i < a.size(); i++) ops.add(new Op(Type.SAME, a.get(i)));
    return ops;
  }

  private static String hunks(List<Op> ops, int context) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < ops.size()) {
      if (ops.get(i).type == Type.SAME) {
        i++;
        continue;
      }
      int start = Math.max(0, i - context);
      int lastChange = i;
      int j = i;
      while (j < ops.size()) {
        if (ops.get(j).type != Type.SAME) lastChange = j;
        else if (j - lastChange > 2 * context) break;
        j++;
      }
      int end = Math.min(ops.size(), lastChange + context + 1);
      int oldLine = 1, newLine = 1;
      for (int k = 0; k < start; k++) {
        if (ops.get(k).type != Type.INSERT) oldLine++;
        if (ops.get(k).type != Type.DELETE) newLine++;
      }
      int oldCount = 0, newCount = 0;
      StringBuilder body = new StringBuilder();
      for (int k = start; k < end; k++) {
        Op op = ops.get(k);
        if (op.type != Type.INSERT) oldCount++;
        if (op.type != Type.DELETE) newCount++;
        body.append(op.type == Type.SAME ? ' ' : op.type == Type.DELETE ? '-' : '+')
            .append(op.line)
            .append('\n');
      }
      out.append("@@ -")
          .append(oldLine)
          .append(',')
          .append(oldCount)
          .append(" +")
          .append(newLine)
          .append(',')
          .append(newCount)
          .append(" @@\n")
          .append(body);
      i = end;
    }
    return out.toString();
  }
}
