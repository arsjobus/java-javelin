package com.javelin.editor;

import com.javelin.editor.SyntaxHighlighter.Span;
import java.util.List;
import java.util.Set;

/**
 * Finds the highlightable tokens of YAML ({@code .yml}, {@code .yaml}): {@code #} comments, mapping
 * keys (as types), quoted strings, block scalars ({@code |} and {@code >}, coloured as strings up
 * to the end of their indented block), numbers, {@code true}/{@code false}/{@code null}/{@code ~}
 * (as keywords), anchors, aliases, tags, {@code ---} document markers and {@code %} directives (as
 * annotations), including inside flow collections such as {@code [a, b]} and {@code {k: v}}. Like
 * the other scanners it works one line at a time. JavaFX-free; {@link SyntaxHighlighter} hands YAML
 * files to {@link #scan}.
 */
final class YamlSyntax {
  private YamlSyntax() {}

  private static int add(List<Span> spans, int start, int end, String style) {
    spans.add(new Span(start, end, style));
    return end;
  }

  /**
   * Words that YAML reads as a fixed value rather than text (the YAML 1.2 core schema spellings).
   */
  private static final Set<String> YAML_LITERALS =
      Set.of("true", "True", "TRUE", "false", "False", "FALSE", "null", "Null", "NULL", "~");

  /**
   * Finds YAML tokens one line at a time. Mapping keys are {@code type}, quoted strings and the
   * lines of a block scalar are {@code string}, {@code true}/{@code null} and friends are {@code
   * keyword}, numbers are {@code number}, comments are {@code comment}, and anchors, aliases, tags,
   * directives and {@code ---}/{@code ...} markers are {@code annotation}. Plain text values stay
   * in the editor's normal colour.
   */
  static void scan(String text, List<Span> spans) {
    int n = text.length();
    int lineStart = 0;
    int blockParent =
        -1; // the column of the node that owns the block scalar being read, or -1 outside one
    while (lineStart < n) {
      int newline = text.indexOf('\n', lineStart);
      int lineEnd = newline < 0 ? n : newline;
      int end = lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
      int indent = lineStart;
      while (indent < end && text.charAt(indent) == ' ') indent++;
      if (blockParent >= 0 && (indent == end || indent - lineStart > blockParent)) {
        if (indent < end)
          add(spans, indent, end, "string"); // a '#' inside a block scalar is text, not a comment
      } else {
        blockParent = -1;
        blockParent = yamlLine(text, lineStart, indent, end, spans);
      }
      lineStart = newline < 0 ? n : newline + 1;
    }
  }

  /**
   * Scans one YAML line; returns the column of the node that opened a block scalar on it, or -1
   * when none was opened.
   */
  private static int yamlLine(String text, int lineStart, int from, int end, List<Span> spans) {
    int i = from;
    if (i >= end) return -1;
    if (text.charAt(i) == '%' && i == lineStart) {
      add(spans, i, end, "annotation");
      return -1;
    } // %YAML, %TAG
    if (i == lineStart
        && (text.startsWith("---", i) || text.startsWith("...", i))
        && (i + 3 >= end || text.charAt(i + 3) == ' ')) {
      add(spans, i, i + 3, "annotation");
      i += 3;
    }
    int nodeColumn = i - lineStart;
    while (i < end && text.charAt(i) == ' ') i++;
    while (i < end
        && text.charAt(i) == '-'
        && (i + 1 >= end
            || text.charAt(i + 1) == ' ')) { // sequence entries, possibly nested: "- - a"
      nodeColumn = i - lineStart;
      i++;
      while (i < end && text.charAt(i) == ' ') i++;
    }
    int keyEnd = yamlKeyEnd(text, i, end);
    if (keyEnd >= 0) {
      nodeColumn = i - lineStart;
      add(spans, i, keyEnd, "type");
      i = keyEnd;
      while (i < end && text.charAt(i) == ' ') i++;
      i++; // the colon
    }
    return yamlValue(text, i, end, nodeColumn, spans);
  }

  /**
   * The end of the mapping key that starts at {@code start} (up to its colon, trailing spaces
   * excluded), or -1 if the text there is not a key.
   */
  private static int yamlKeyEnd(String text, int start, int end) {
    if (start >= end) return -1;
    char first = text.charAt(start);
    int i = start;
    if (first == '"' || first == '\'') {
      i = yamlQuotedEnd(text, start, end);
    } else {
      if ("#|>&*!%@`[{".indexOf(first) >= 0) return -1;
      for (; i < end; i++) {
        char c = text.charAt(i);
        if (c == ':' && (i + 1 >= end || text.charAt(i + 1) == ' ')) break;
        if (c == '#' && i > start && text.charAt(i - 1) == ' ') return -1;
      }
      if (i >= end) return -1;
      int keyEnd = i;
      while (keyEnd > start && text.charAt(keyEnd - 1) == ' ') keyEnd--;
      return keyEnd > start ? keyEnd : -1;
    }
    int after = i;
    while (after < end && text.charAt(after) == ' ') after++;
    return after < end
            && text.charAt(after) == ':'
            && (after + 1 >= end || text.charAt(after + 1) == ' ')
        ? i
        : -1;
  }

  /**
   * End (exclusive) of the quoted scalar that starts at {@code start}; the end of the line when it
   * is not closed there.
   */
  private static int yamlQuotedEnd(String text, int start, int end) {
    char quote = text.charAt(start);
    for (int i = start + 1; i < end; i++) {
      char c = text.charAt(i);
      if (quote == '"' && c == '\\') i++;
      else if (c == quote) {
        if (quote == '\'' && i + 1 < end && text.charAt(i + 1) == '\'')
          i++; // '' is an escaped quote
        else return i + 1;
      }
    }
    return end;
  }

  /**
   * Scans the rest of a line as YAML values; returns {@code nodeColumn} when a block scalar starts
   * here, otherwise -1.
   */
  private static int yamlValue(String text, int from, int end, int nodeColumn, List<Span> spans) {
    int i = from;
    int flowDepth = 0;
    boolean first =
        true; // a '|' or '>' only starts a block scalar as the very first thing of the value
    while (i < end) {
      char c = text.charAt(i);
      if (c == ' ') {
        i++;
        continue;
      }
      if (c == '#' && (i == from || text.charAt(i - 1) == ' ')) {
        add(spans, i, end, "comment");
        return -1;
      }
      boolean wasFirst = first;
      first = false;
      if (c == '"' || c == '\'') {
        int close = yamlQuotedEnd(text, i, end);
        boolean key = flowDepth > 0 && yamlColonFollows(text, close, end);
        add(spans, i, close, key ? "type" : "string");
        i = close;
      } else if (c == '[' || c == '{') {
        flowDepth++;
        i++;
        first = true;
      } else if (c == ']' || c == '}') {
        if (flowDepth > 0) flowDepth--;
        i++;
      } else if (c == ','
          || c == ':'
              && flowDepth > 0
              && (i + 1 >= end || " ,]}".indexOf(text.charAt(i + 1)) >= 0)) {
        i++;
        first = true;
      } // separators in a flow collection
      else if ((c == '|' || c == '>')
          && wasFirst
          && flowDepth == 0
          && yamlBlockHeaderEnd(text, i, end) >= 0) {
        int header = yamlBlockHeaderEnd(text, i, end);
        add(spans, i, header, "annotation");
        i = header;
        while (i < end && text.charAt(i) == ' ') i++;
        if (i < end)
          add(spans, i, end, "comment"); // yamlBlockHeaderEnd guarantees only a comment can follow
        return nodeColumn;
      } else if (c == '&' || c == '*' || c == '!') {
        int close = i + 1;
        while (close < end && " ,[]{}".indexOf(text.charAt(close)) < 0) close++;
        add(spans, i, close, "annotation");
        i = close;
        first = wasFirst; // "key: &anchor |" and "key: !!str |" still start a block scalar
      } else {
        int close = i;
        while (close < end) {
          char d = text.charAt(close);
          if (d == '#' && close > i && text.charAt(close - 1) == ' ') break;
          if (flowDepth > 0 && (d == ',' || d == ']' || d == '}')) break;
          if (d == ':'
              && flowDepth > 0
              && (close + 1 >= end || " ,]}".indexOf(text.charAt(close + 1)) >= 0)) break;
          close++;
        }
        int tokenEnd = close;
        while (tokenEnd > i && text.charAt(tokenEnd - 1) == ' ') tokenEnd--;
        String token = text.substring(i, tokenEnd);
        if (flowDepth > 0 && yamlColonFollows(text, close, end)) add(spans, i, tokenEnd, "type");
        else if (YAML_LITERALS.contains(token)) add(spans, i, tokenEnd, "keyword");
        else if (isYamlNumber(token)) add(spans, i, tokenEnd, "number");
        i = Math.max(close, i + 1);
      }
    }
    return -1;
  }

  private static boolean yamlColonFollows(String text, int from, int end) {
    int i = from;
    while (i < end && text.charAt(i) == ' ') i++;
    return i < end
        && text.charAt(i) == ':'
        && (i + 1 >= end || " ,]}".indexOf(text.charAt(i + 1)) >= 0);
  }

  /**
   * End of a block scalar header ({@code |}, {@code >-}, {@code |2+}) that is followed by nothing
   * but an optional comment, else -1.
   */
  private static int yamlBlockHeaderEnd(String text, int start, int end) {
    int i = start + 1;
    while (i < end && "+-0123456789".indexOf(text.charAt(i)) >= 0) i++;
    int rest = i;
    while (rest < end && text.charAt(rest) == ' ') rest++;
    return rest >= end || text.charAt(rest) == '#' && rest > i ? i : -1;
  }

  /**
   * Plain YAML numbers: integers, decimals, exponents, {@code 0x} and {@code 0o} forms, {@code
   * .inf} and {@code .nan}.
   */
  private static boolean isYamlNumber(String token) {
    int n = token.length();
    if (n == 0) return false;
    int i = token.charAt(0) == '-' || token.charAt(0) == '+' ? 1 : 0;
    String rest = token.substring(i);
    if (rest.equals(".inf")
        || rest.equals(".Inf")
        || rest.equals(".INF")
        || (i == 0 && (rest.equals(".nan") || rest.equals(".NaN") || rest.equals(".NAN"))))
      return true;
    if (rest.startsWith("0x") && rest.length() > 2)
      return rest.substring(2).chars().allMatch(ch -> Character.digit(ch, 16) >= 0);
    if (rest.startsWith("0o") && rest.length() > 2)
      return rest.substring(2).chars().allMatch(ch -> ch >= '0' && ch <= '7');
    int digits = 0;
    int j = 0, len = rest.length();
    while (j < len && Character.isDigit(rest.charAt(j))) {
      j++;
      digits++;
    }
    if (j < len && rest.charAt(j) == '.') {
      j++;
      while (j < len && Character.isDigit(rest.charAt(j))) {
        j++;
        digits++;
      }
    }
    if (digits == 0) return false;
    if (j < len && (rest.charAt(j) == 'e' || rest.charAt(j) == 'E')) {
      j++;
      if (j < len && (rest.charAt(j) == '-' || rest.charAt(j) == '+')) j++;
      int exponent = j;
      while (j < len && Character.isDigit(rest.charAt(j))) j++;
      if (j == exponent) return false;
    }
    return j == len;
  }
}
