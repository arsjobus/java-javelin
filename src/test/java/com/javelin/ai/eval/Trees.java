package com.javelin.ai.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Copying, reading and overlaying small project folders. */
final class Trees {
  private Trees() {}

  /** Copies the files of {@code from} into {@code to} (created), keeping the layout. */
  static void copy(Path from, Path to) throws IOException {
    for (Map.Entry<String, String> e : read(from).entrySet()) write(to, e.getKey(), e.getValue());
  }

  /** Writes the files of {@code overlay} over {@code target}, replacing or adding. */
  static void overlay(Path overlay, Path target) throws IOException {
    copy(overlay, target);
  }

  static void write(Path root, String relative, String text) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /** Every regular file under {@code root} by its '/'-separated relative path. */
  static Map<String, String> read(Path root) throws IOException {
    Map<String, String> files = new TreeMap<>();
    if (!Files.isDirectory(root)) return files;
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile).toList())
        files.put(
            root.relativize(p).toString().replace('\\', '/'),
            Files.readString(p, StandardCharsets.UTF_8));
    }
    return files;
  }

  /**
   * The character range from the start of the first line whose text, stripped, is {@code from} to
   * the end (after the line break) of the first later line whose stripped text is {@code to} and
   * that is indented exactly like the {@code from} line. The indentation rule is what lets a task
   * say "this method" or "this class" with its closing brace, whatever blocks are nested inside.
   */
  static int[] selection(String text, String from, String to) {
    int start = -1, end = -1, at = 0;
    String indent = "";
    while (at < text.length()) {
      int eol = text.indexOf('\n', at);
      int next = eol < 0 ? text.length() : eol + 1;
      String raw = text.substring(at, eol < 0 ? text.length() : eol);
      String line = raw.strip();
      String lineIndent = raw.substring(0, raw.length() - raw.stripLeading().length());
      if (start < 0 && line.equals(from.strip())) {
        start = at;
        indent = lineIndent;
      } else if (start >= 0 && line.equals(to.strip()) && lineIndent.equals(indent)) {
        end = next;
        break;
      }
      at = next;
    }
    if (start < 0) throw new IllegalArgumentException("no line '" + from + "' in the file");
    if (end < 0)
      throw new IllegalArgumentException(
          "no line '" + to + "' indented like '" + from + "' after it");
    return new int[] {start, end};
  }
}
