package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What the scanner skips and how much it will take. {@code exclude} holds {@code .gitignore}-style
 * patterns the user added (a name, a folder with a trailing slash, a glob such as {@code *.sql}),
 * on top of what is always skipped.
 */
public record ScanOptions(
    List<String> exclude, long maxFileBytes, int maxFiles, boolean useGitignore) {
  public static final long DEFAULT_MAX_FILE_BYTES = 100_000;
  public static final long MIN_MAX_FILE_BYTES = 1_000;
  public static final long MAX_MAX_FILE_BYTES = 2_000_000;
  public static final int DEFAULT_MAX_FILES = 5_000;
  public static final int MIN_MAX_FILES = 10;
  public static final int MAX_MAX_FILES = 100_000;

  public ScanOptions {
    exclude = List.copyOf(exclude);
  }

  public static ScanOptions defaults() {
    return new ScanOptions(List.of(), DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_FILES, true);
  }

  public ScanOptions withExclude(Collection<String> patterns) {
    List<String> all = new ArrayList<>(exclude);
    all.addAll(patterns);
    return new ScanOptions(all, maxFileBytes, maxFiles, useGitignore);
  }

  public ScanOptions withLimits(long fileBytes, int files) {
    return new ScanOptions(exclude, fileBytes, files, useGitignore);
  }

  public ScanOptions withGitignore(boolean use) {
    return new ScanOptions(exclude, maxFileBytes, maxFiles, use);
  }
}
