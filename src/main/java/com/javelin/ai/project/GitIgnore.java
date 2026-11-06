package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules of one {@code .gitignore} file (or of a list of patterns written the same way): blank
 * lines and {@code #} comments, {@code !} to take a file back, a trailing {@code /} for folders
 * only, a leading or inner {@code /} to anchor to the file's folder, and {@code *}, {@code ?},
 * {@code [a-z]} and {@code **} wildcards. It does not run Git, so it cannot know about {@code
 * .git/info/exclude} or the user's global ignore file. JavaFX-free.
 */
public final class GitIgnore {
  private record Rule(Pattern pattern, boolean negated, boolean directoryOnly) {}

  private final String base;
  private final List<Rule> rules = new ArrayList<>();

  /** {@code base} is the folder these rules belong to, relative to the project ("" for the top). */
  private GitIgnore(String base) {
    this.base = base;
  }

  public static GitIgnore parse(List<String> lines, String base) {
    GitIgnore ignore = new GitIgnore(base);
    for (String raw : lines) {
      String line = raw.stripTrailing();
      if (line.isEmpty() || line.startsWith("#")) continue;
      boolean negated = line.startsWith("!");
      if (negated) line = line.substring(1);
      if (line.startsWith("\\#") || line.startsWith("\\!")) line = line.substring(1);
      boolean directoryOnly = line.endsWith("/");
      while (line.endsWith("/")) line = line.substring(0, line.length() - 1);
      if (line.isEmpty()) continue;
      boolean anchored = line.indexOf('/') >= 0;
      if (line.startsWith("/")) line = line.substring(1);
      ignore.rules.add(new Rule(regex(line, anchored), negated, directoryOnly));
    }
    return ignore;
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }

  /**
   * The verdict of this file's rules for {@code path} (relative to the project): true ignored,
   * false taken back by a {@code !} rule, null when no rule speaks. The last matching rule wins.
   */
  public Boolean verdict(String path, boolean directory) {
    String relative;
    if (base.isEmpty()) relative = path;
    else if (path.startsWith(base + "/")) relative = path.substring(base.length() + 1);
    else return null;
    Boolean result = null;
    for (Rule rule : rules) {
      if (rule.directoryOnly() && !directory) continue;
      if (rule.pattern().matcher(relative).matches()) result = !rule.negated();
    }
    return result;
  }

  private static Pattern regex(String glob, boolean anchored) {
    StringBuilder out = new StringBuilder();
    if (!anchored) out.append("(?:.*/)?");
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          boolean atStart = i == 0 || glob.charAt(i - 1) == '/';
          boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
          if (atStart && slashAfter) {
            out.append("(?:.*/)?");
            i += 3;
            continue;
          }
          if (atStart && i + 2 == glob.length()) {
            out.append(".*");
            i += 2;
            continue;
          }
          out.append("[^/]*");
          i += 2;
          continue;
        }
        out.append("[^/]*");
      } else if (c == '?') {
        out.append("[^/]");
      } else if (c == '[') {
        int close = glob.indexOf(']', i + 2);
        if (close < 0) {
          out.append("\\[");
        } else {
          String set = glob.substring(i + 1, close);
          if (set.startsWith("!")) set = "^" + set.substring(1);
          out.append('[').append(set.replace("\\", "\\\\")).append(']');
          i = close;
        }
      } else if (c == '\\' && i + 1 < glob.length()) {
        out.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
      } else {
        out.append(Pattern.quote(String.valueOf(c)));
      }
      i++;
    }
    return Pattern.compile(out.toString());
  }
}
