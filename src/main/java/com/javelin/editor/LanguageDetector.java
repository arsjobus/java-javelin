package com.javelin.editor;

import com.javelin.editor.SyntaxHighlighter.Language;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which {@link Language} a file is highlighted as, from its name and, for a file without an
 * extension, its {@code #!} first line. Kept apart from the scanner so that "which language is this
 * file" and "how is that language tokenised" can change independently.
 */
final class LanguageDetector {
  private LanguageDetector() {}

  /**
   * Extensions (lower case, no dot) of files whose syntax the scanner understands, and the language
   * each one uses.
   */
  private static final Map<String, Language> EXTENSIONS =
      Map.ofEntries(
          Map.entry("java", Language.JAVA),
          Map.entry("kt", Language.KOTLIN),
          Map.entry("kts", Language.KOTLIN),
          Map.entry("js", Language.JAVASCRIPT),
          Map.entry("mjs", Language.JAVASCRIPT),
          Map.entry("cjs", Language.JAVASCRIPT),
          Map.entry("jsx", Language.JAVASCRIPT),
          Map.entry("ts", Language.TYPESCRIPT),
          Map.entry("tsx", Language.TYPESCRIPT),
          Map.entry("c", Language.C),
          Map.entry("h", Language.CPP), // a header may be C or C++; the C++ list is a superset
          Map.entry("cpp", Language.CPP),
          Map.entry("cc", Language.CPP),
          Map.entry("cxx", Language.CPP),
          Map.entry("hpp", Language.CPP),
          Map.entry("hh", Language.CPP),
          Map.entry("go", Language.GO),
          Map.entry("rs", Language.RUST),
          Map.entry("php", Language.PHP),
          Map.entry("json", Language.JSON),
          Map.entry("py", Language.PYTHON),
          Map.entry("pyi", Language.PYTHON),
          Map.entry("pyw", Language.PYTHON),
          Map.entry("html", Language.HTML),
          Map.entry("htm", Language.HTML),
          Map.entry("xhtml", Language.HTML),
          Map.entry("xml", Language.XML),
          Map.entry("xsd", Language.XML),
          Map.entry("xsl", Language.XML),
          Map.entry("xslt", Language.XML),
          Map.entry("svg", Language.XML),
          Map.entry("fxml", Language.XML),
          Map.entry("plist", Language.XML),
          Map.entry("rss", Language.XML),
          Map.entry("atom", Language.XML),
          Map.entry("wsdl", Language.XML),
          Map.entry("md", Language.MARKDOWN),
          Map.entry("markdown", Language.MARKDOWN),
          Map.entry("yml", Language.YAML),
          Map.entry("yaml", Language.YAML),
          Map.entry("sh", Language.SHELL),
          Map.entry("bash", Language.SHELL),
          Map.entry("zsh", Language.SHELL),
          Map.entry("ksh", Language.SHELL),
          Map.entry("command", Language.SHELL));

  /** Shell start-up files that have no extension, by name (compared in lower case). */
  private static final Set<String> SHELL_FILE_NAMES =
      Set.of(
          ".bashrc",
          ".bash_profile",
          ".bash_login",
          ".bash_logout",
          ".bash_aliases",
          ".profile",
          ".zshrc",
          ".zprofile",
          ".zshenv",
          ".zlogin",
          ".zlogout",
          ".kshrc",
          ".envrc");

  /**
   * Programs a {@code #!} line may name that run shell scripts (compared in lower case, without the
   * folder).
   */
  private static final Set<String> SHELL_PROGRAMS =
      Set.of("sh", "bash", "dash", "ash", "ksh", "mksh", "zsh", "rbash");

  /**
   * How much of the first line is looked at for a {@code #!} line; a longer one is not a real
   * interpreter line.
   */
  private static final int MAX_SHEBANG_CHARS = 256;

  /**
   * The language whose rules the file gets, going by its name alone: {@link
   * SyntaxHighlighter.Language#JAVA} for an untitled buffer (the editor is Java-oriented), the
   * language of a known extension (C-family, Python, HTML, XML, Markdown, YAML or shell), {@link
   * SyntaxHighlighter.Language#SHELL} for the well-known shell start-up files ({@code .bashrc},
   * {@code .zshrc}, {@code .profile} and so on), and {@code null} for any other file, which is
   * shown as plain text. See {@link #of(File, String)} for files whose first line names their
   * interpreter.
   */
  static Language of(File file) {
    if (file == null) return Language.JAVA;
    String name = file.getName();
    if (SHELL_FILE_NAMES.contains(name.toLowerCase(Locale.ROOT))) return Language.SHELL;
    int dot = name.lastIndexOf('.');
    return dot < 0 ? null : EXTENSIONS.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * Like {@link #of(File)}, but a file with no extension whose first line is {@code #!} naming a
   * shell ({@code #!/bin/bash}, {@code #!/usr/bin/env zsh}), Python or Node gets that language. A
   * file that has an extension is never second-guessed: {@code notes.txt} stays plain text whatever
   * its first line says.
   */
  static Language of(File file, String text) {
    Language byName = of(file);
    if (byName != null || file == null || hasExtension(file.getName())) return byName;
    return ofShebang(text);
  }

  private static boolean hasExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 && dot < name.length() - 1; // ".hidden" and "trailing." have none
  }

  /**
   * The language named by a {@code #!} first line, or null when the text has none or names an
   * unknown program.
   */
  static Language ofShebang(String text) {
    if (text == null || !text.startsWith("#!")) return null;
    int end = text.indexOf('\n');
    end = Math.min(end < 0 ? text.length() : end, MAX_SHEBANG_CHARS);
    String[] words = text.substring(2, end).strip().split("\\s+");
    String program = baseName(words[0]);
    if (program.equals("env")) { // #!/usr/bin/env [-S] [NAME=value] program
      program = "";
      for (int i = 1; i < words.length; i++) {
        if (words[i].startsWith("-") || words[i].indexOf('=') > 0) continue;
        program = baseName(words[i]);
        break;
      }
    }
    program = program.toLowerCase(Locale.ROOT);
    if (SHELL_PROGRAMS.contains(program)) return Language.SHELL;
    if (program.matches("python(\\d+(\\.\\d+)*)?"))
      return Language.PYTHON; // python, python3, python3.12
    if (program.equals("node") || program.equals("nodejs")) return Language.JAVASCRIPT;
    return null;
  }

  private static String baseName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
