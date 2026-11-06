package com.javelin.ai.project;

import com.javelin.ai.SensitiveFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds the files of the open project that may be shown to a model. It walks the project folder
 * only (never up, never through a symbolic link), and leaves out, with a reason each: what {@code
 * .gitignore} ignores, build output and caches, binary files, files that hold credentials, files
 * over the size limit and what the user excluded. The result does not depend on the order the file
 * system lists things in. JavaFX-free; it reads no file content beyond a short look at the start of
 * a file to tell text from binary.
 */
public final class ProjectScanner {
  private ProjectScanner() {}

  /** Folders that are build output, caches, tool state or version control, wherever they are. */
  static final Set<String> GENERATED_DIRECTORIES =
      Set.of(
          ".git",
          ".hg",
          ".svn",
          "target",
          "build",
          "out",
          "dist",
          "node_modules",
          ".gradle",
          ".idea",
          ".vscode",
          ".settings",
          ".javelin-ide",
          "__pycache__",
          ".venv",
          "venv",
          ".mypy_cache",
          ".pytest_cache",
          ".tox",
          ".next",
          ".cache",
          "coverage");

  /** Folders that hold keys and credentials by design. */
  static final Set<String> SENSITIVE_DIRECTORIES = Set.of(".ssh", ".aws", ".gnupg", ".kube");

  static final Set<String> GENERATED_FILES =
      Set.of(
          ".ds_store",
          "thumbs.db",
          "package-lock.json",
          "yarn.lock",
          "pnpm-lock.yaml",
          "cargo.lock",
          "poetry.lock",
          "gradle.lockfile");

  private static final List<String> GENERATED_SUFFIXES =
      List.of(".min.js", ".min.css", ".map", ".log");

  private static final Set<String> BINARY_EXTENSIONS =
      Set.of(
          "class",
          "jar",
          "war",
          "ear",
          "zip",
          "gz",
          "tgz",
          "bz2",
          "xz",
          "7z",
          "rar",
          "tar",
          "png",
          "jpg",
          "jpeg",
          "gif",
          "bmp",
          "ico",
          "icns",
          "webp",
          "tif",
          "tiff",
          "pdf",
          "doc",
          "docx",
          "xls",
          "xlsx",
          "ppt",
          "pptx",
          "odt",
          "mp3",
          "mp4",
          "mov",
          "avi",
          "wav",
          "ogg",
          "flac",
          "ttf",
          "otf",
          "woff",
          "woff2",
          "eot",
          "exe",
          "dll",
          "so",
          "dylib",
          "bin",
          "o",
          "a",
          "lib",
          "obj",
          "pyc",
          "pyo",
          "db",
          "sqlite",
          "dmg",
          "iso",
          "jks",
          "keystore");

  static final int MAX_EXCLUDED_LISTED = 2_000;
  private static final int MAX_DEPTH = 64;
  private static final long MAX_GITIGNORE_BYTES = 256 * 1024;
  private static final int SNIFF_BYTES = 8192;

  private static final class Scan {
    final Path root;
    final ScanOptions options;
    final GitIgnore user;
    final Map<String, ProjectFile> known;
    final List<ProjectFile> files = new ArrayList<>();
    final List<ProjectIndex.Excluded> excluded = new ArrayList<>();
    int excludedTotal;
    boolean limited;

    Scan(Path root, ScanOptions options, Map<String, ProjectFile> known) {
      this.root = root;
      this.options = options;
      this.known = known;
      this.user = GitIgnore.parse(options.exclude(), "");
    }

    void skip(String path, ProjectIndex.Reason reason) {
      excludedTotal++;
      if (excluded.size() < MAX_EXCLUDED_LISTED)
        excluded.add(new ProjectIndex.Excluded(path, reason));
    }
  }

  /**
   * @throws IOException when {@code root} is not a readable folder
   */
  public static ProjectIndex scan(Path root, ScanOptions options) throws IOException {
    return scan(root, options, Map.of());
  }

  private static ProjectIndex scan(Path root, ScanOptions options, Map<String, ProjectFile> known)
      throws IOException {
    Path real = root.toRealPath();
    if (!Files.isDirectory(real)) throw new IOException(root + " is not a folder.");
    Scan scan = new Scan(real, options, known);
    walk(scan, real, "", withRulesOf(scan, real, "", List.of()), 0);
    scan.files.sort(Comparator.comparing(ProjectFile::path));
    scan.excluded.sort(Comparator.comparing(ProjectIndex.Excluded::path));
    return new ProjectIndex(real, scan.files, scan.excluded, scan.excludedTotal, scan.limited);
  }

  private static void walk(Scan scan, Path dir, String relDir, List<GitIgnore> rules, int depth)
      throws IOException {
    List<Path> entries;
    try (Stream<Path> list = Files.list(dir)) {
      entries = list.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
    } catch (IOException ex) {
      scan.skip(relDir.isEmpty() ? "./" : relDir + "/", ProjectIndex.Reason.UNREADABLE);
      return;
    }
    for (Path entry : entries) {
      String name = entry.getFileName().toString();
      String rel = relDir.isEmpty() ? name : relDir + "/" + name;
      BasicFileAttributes attrs;
      try {
        attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException ex) {
        scan.skip(rel, ProjectIndex.Reason.UNREADABLE);
        continue;
      }
      if (attrs.isSymbolicLink()) {
        scan.skip(rel, ProjectIndex.Reason.SYMLINK);
      } else if (attrs.isDirectory()) {
        visitDirectory(scan, entry, name, rel, rules, depth);
      } else if (attrs.isRegularFile()) {
        visitFile(scan, entry, name, rel, attrs, rules);
      } else {
        scan.skip(rel, ProjectIndex.Reason.UNREADABLE);
      }
    }
  }

  private static void visitDirectory(
      Scan scan, Path dir, String name, String rel, List<GitIgnore> rules, int depth)
      throws IOException {
    String lower = name.toLowerCase(Locale.ROOT);
    if (SENSITIVE_DIRECTORIES.contains(lower)) {
      scan.skip(rel + "/", ProjectIndex.Reason.SENSITIVE);
    } else if (GENERATED_DIRECTORIES.contains(lower)) {
      scan.skip(rel + "/", ProjectIndex.Reason.GENERATED);
    } else if (ignored(rules, rel, true)) {
      scan.skip(rel + "/", ProjectIndex.Reason.GITIGNORE);
    } else if (Boolean.TRUE.equals(scan.user.verdict(rel, true))) {
      scan.skip(rel + "/", ProjectIndex.Reason.EXCLUDED);
    } else if (depth >= MAX_DEPTH) {
      scan.skip(rel + "/", ProjectIndex.Reason.LIMIT);
    } else {
      walk(scan, dir, rel, withRulesOf(scan, dir, rel, rules), depth + 1);
    }
  }

  private static void visitFile(
      Scan scan,
      Path file,
      String name,
      String rel,
      BasicFileAttributes attrs,
      List<GitIgnore> rules) {
    String lower = name.toLowerCase(Locale.ROOT);
    long modified = attrs.lastModifiedTime().toMillis();
    ProjectIndex.Reason reason = null;
    if (SensitiveFiles.isSensitive(name)) reason = ProjectIndex.Reason.SENSITIVE;
    else if (isGenerated(lower)) reason = ProjectIndex.Reason.GENERATED;
    else if (ignored(rules, rel, false)) reason = ProjectIndex.Reason.GITIGNORE;
    else if (Boolean.TRUE.equals(scan.user.verdict(rel, false)))
      reason = ProjectIndex.Reason.EXCLUDED;
    else if (attrs.size() > scan.options.maxFileBytes()) reason = ProjectIndex.Reason.TOO_LARGE;
    else if (!knownText(scan, rel, attrs.size(), modified) && isBinary(file, lower))
      reason = ProjectIndex.Reason.BINARY;
    else if (scan.files.size() >= scan.options.maxFiles()) {
      reason = ProjectIndex.Reason.LIMIT;
      scan.limited = true;
    }
    if (reason != null) {
      scan.skip(rel, reason);
      return;
    }
    String language = FileKinds.languageOf(rel);
    scan.files.add(
        new ProjectFile(rel, attrs.size(), modified, language, FileKinds.kindOf(rel, language)));
  }

  /** True when an earlier scan took this very file (same size and time) as text. */
  private static boolean knownText(Scan scan, String rel, long size, long modified) {
    ProjectFile before = scan.known.get(rel);
    return before != null && before.size() == size && before.modified() == modified;
  }

  private static List<GitIgnore> withRulesOf(
      Scan scan, Path dir, String rel, List<GitIgnore> inherited) {
    if (!scan.options.useGitignore()) return inherited;
    Path file = dir.resolve(".gitignore");
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > MAX_GITIGNORE_BYTES) return inherited;
      GitIgnore own = GitIgnore.parse(Files.readAllLines(file, StandardCharsets.UTF_8), rel);
      if (own.isEmpty()) return inherited;
      List<GitIgnore> all = new ArrayList<>(inherited);
      all.add(own);
      return all;
    } catch (IOException ex) {
      return inherited; // an unreadable .gitignore ignores nothing; the files are then judged alone
    }
  }

  /** The verdict of the closest rule file that has an opinion; deeper files override higher. */
  private static boolean ignored(List<GitIgnore> rules, String rel, boolean directory) {
    for (int i = rules.size() - 1; i >= 0; i--) {
      Boolean verdict = rules.get(i).verdict(rel, directory);
      if (verdict != null) return verdict;
    }
    return false;
  }

  private static boolean isGenerated(String lowerName) {
    if (GENERATED_FILES.contains(lowerName)) return true;
    for (String suffix : GENERATED_SUFFIXES) if (lowerName.endsWith(suffix)) return true;
    return false;
  }

  /** Binary by extension, or because the start of the file has a NUL byte or is not UTF-8. */
  static boolean isBinary(Path file, String lowerName) {
    int dot = lowerName.lastIndexOf('.');
    if (dot >= 0 && BINARY_EXTENSIONS.contains(lowerName.substring(dot + 1))) return true;
    try (InputStream in = Files.newInputStream(file)) {
      byte[] head = in.readNBytes(SNIFF_BYTES);
      for (byte b : head) if (b == 0) return true;
      CharsetDecoder decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decoder
          .decode(ByteBuffer.wrap(head), java.nio.CharBuffer.allocate(head.length + 1), false)
          .isError();
    } catch (IOException ex) {
      return true; // a file that cannot even be opened is not text we can send
    }
  }

  /**
   * Looks at the project again, reusing what is known about files whose size and time did not
   * change (they are not looked into again to tell text from binary). The result is the same as a
   * fresh {@link #scan}, which a test checks.
   */
  public static ProjectIndex refresh(ProjectIndex previous, ScanOptions options)
      throws IOException {
    Map<String, ProjectFile> known = new java.util.HashMap<>();
    for (ProjectFile f : previous.files()) known.put(f.path(), f);
    return scan(previous.root(), options, known);
  }
}
