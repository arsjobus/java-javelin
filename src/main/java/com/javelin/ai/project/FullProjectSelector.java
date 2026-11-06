package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Full Project: every eligible file, in a fixed order of importance, so that when not everything
 * fits the model still gets the files that explain the project first: architecture documents, build
 * configuration, source, tests, then the rest. Inside a group, files go by path.
 */
public final class FullProjectSelector {
  private FullProjectSelector() {}

  public static List<Pick> select(ProjectIndex index) {
    List<ProjectFile> files = new ArrayList<>(index.files());
    files.sort(Comparator.comparingInt(FullProjectSelector::tier).thenComparing(ProjectFile::path));
    List<Pick> picks = new ArrayList<>();
    int n = files.size();
    for (int i = 0; i < n; i++) {
      ProjectFile f = files.get(i);
      picks.add(new Pick(f.path(), reason(f), n - i));
    }
    return picks;
  }

  static boolean isOverviewDocument(ProjectFile f) {
    String lower = f.path().toLowerCase(Locale.ROOT);
    String name = f.name().toLowerCase(Locale.ROOT);
    return f.kind() == ProjectFile.Kind.DOC
        && (name.startsWith("readme")
            || name.startsWith("architecture")
            || name.startsWith("agents")
            || name.startsWith("design")
            || name.startsWith("contributing")
            || name.startsWith("principles")
            || name.startsWith("quick_rules")
            || lower.startsWith("docs/"));
  }

  static int tier(ProjectFile f) {
    if (isOverviewDocument(f)) return 0;
    return switch (f.kind()) {
      case BUILD -> 1;
      case SOURCE -> 2;
      case TEST -> 3;
      case CONFIG -> 4;
      case DOC -> 5;
      case OTHER -> 6;
    };
  }

  private static String reason(ProjectFile f) {
    if (isOverviewDocument(f)) return "project documentation";
    return switch (f.kind()) {
      case BUILD -> "build configuration";
      case SOURCE -> "source";
      case TEST -> "test";
      case CONFIG -> "configuration";
      case DOC -> "documentation";
      case OTHER -> "project file";
    };
  }
}
