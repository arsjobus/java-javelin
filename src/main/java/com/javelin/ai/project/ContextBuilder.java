package com.javelin.ai.project;

import com.javelin.ai.AiException;
import com.javelin.ai.FailureReason;
import com.javelin.ai.SensitiveFiles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns the files a selector wants into the project text the model is sent, within a token budget.
 * Rules it keeps: file paths are preserved; every file sits between markers that name it and that
 * its own text cannot fake; the order and the text depend only on the inputs (no clock, no
 * randomness); a file goes in whole or not at all (nothing is ever cut in half); and what did not
 * go in is listed with the reason. JavaFX-free and independent of any provider.
 */
public final class ContextBuilder {
  private ContextBuilder() {}

  private static final int MAX_DEPENDENCY_LINES = 60;

  private record Candidate(String path, String reason, boolean fromUser) {}

  private record Loaded(
      Candidate candidate, ProjectFile file, String text, int lines, String hash) {}

  /**
   * @param picks the selector's files in order of importance
   * @param fixedTokens what the rest of the request costs (system message, the user's wording)
   * @param nowMillis written into the snapshot; never into the text
   * @throws AiException REFUSED when not even the overview fits the window
   */
  public static ProjectContext build(
      ProjectIndex index,
      ProjectReader reader,
      ContextMode mode,
      List<Pick> picks,
      Adjustments adjustments,
      ContextBudget budget,
      int fixedTokens,
      ScanOptions options,
      long nowMillis)
      throws AiException {
    Map<String, ProjectFile> eligible = new LinkedHashMap<>();
    for (ProjectFile f : index.files()) eligible.put(f.path(), f);

    List<Candidate> order = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String path : new TreeSet<>(adjustments.include()))
      if (eligible.containsKey(path) && !adjustments.exclude().contains(path) && seen.add(path))
        order.add(new Candidate(path, "added by you", true));
    for (Pick pick : picks)
      if (eligible.containsKey(pick.path())
          && !adjustments.exclude().contains(pick.path())
          && seen.add(pick.path())) order.add(new Candidate(pick.path(), pick.reason(), false));

    List<Omitted> omitted = new ArrayList<>();
    List<Loaded> loaded = new ArrayList<>();
    for (Candidate c : order) {
      ProjectFile file = eligible.get(c.path());
      try {
        String text = reader.read(file, options.maxFileBytes());
        loaded.add(new Loaded(c, file, text, countLines(text), ProjectReader.hash(text)));
      } catch (IOException ex) {
        omitted.add(
            new Omitted(file, Omitted.Why.UNREADABLE, "could not be read: " + messageOf(ex), 0));
      }
    }

    String marker = marker(loaded);
    Map<String, Integer> blockTokens = new HashMap<>();
    for (Loaded l : loaded)
      blockTokens.put(l.file().path(), ContextBudget.estimateTokens(block(marker, l)));

    Overview overview =
        overview(index, reader, mode, options, budget, fixedTokens, eligible.size());

    int remaining = overview.available;
    List<Loaded> fitted = new ArrayList<>();
    for (Loaded l : loaded) {
      int cost = blockTokens.get(l.file().path());
      if (cost <= remaining) {
        fitted.add(l);
        remaining -= cost;
      } else {
        omitted.add(
            new Omitted(
                l.file(),
                Omitted.Why.DOES_NOT_FIT,
                "does not fit: needs about "
                    + ContextBudget.format(cost)
                    + " tokens, "
                    + ContextBudget.format(Math.max(0, remaining))
                    + " left"
                    + (l.candidate().fromUser() ? " (you added it)" : ""),
                cost));
      }
    }
    Set<String> includedPaths = new HashSet<>();
    for (Loaded l : fitted) includedPaths.add(l.file().path());
    for (ProjectFile f : index.files()) {
      if (includedPaths.contains(f.path())) continue;
      if (omitted.stream().anyMatch(o -> o.file().path().equals(f.path()))) continue;
      boolean byUser = adjustments.exclude().contains(f.path());
      omitted.add(
          new Omitted(
              f,
              byUser ? Omitted.Why.LEFT_OUT_BY_USER : Omitted.Why.NOT_SELECTED,
              byUser ? "left out by you" : "not selected as relevant to the request",
              0));
    }
    omitted.sort(Comparator.comparing(o -> o.file().path()));

    StringBuilder text = new StringBuilder();
    text.append(
        header(index, mode, fitted.size(), eligible.size(), omitted, overview.dependencyLines));
    text.append("\nPROJECT STRUCTURE\n")
        .append(tree(index.files(), includedPaths, overview.compact));
    text.append("\nFILES\n");
    List<ContextEntry> entries = new ArrayList<>();
    List<String> warnings = new ArrayList<>(budget.warnings());
    Map<String, String> sentHashes = new TreeMap<>();
    for (Loaded l : fitted) {
      text.append(block(marker, l));
      entries.add(
          new ContextEntry(
              l.file(),
              l.candidate().reason(),
              blockTokens.get(l.file().path()),
              l.hash(),
              l.candidate().fromUser()));
      sentHashes.put(l.file().path(), l.hash());
      for (String what : SensitiveFiles.warnings(l.text()))
        warnings.add(l.file().path() + " looks like it contains " + what + ".");
    }
    if (index.limited())
      warnings.add(
          "The project has more files than the scan limit of "
              + options.maxFiles()
              + "; the files beyond it were never looked at.");
    String body = text.toString();
    int textTokens = ContextBudget.estimateTokens(body);
    Map<String, ProjectSnapshot.Stamp> stamps = new TreeMap<>();
    for (ProjectFile f : index.files())
      stamps.put(f.path(), new ProjectSnapshot.Stamp(f.size(), f.modified()));
    String id = snapshotId(mode, sentHashes);
    return new ProjectContext(
        index.name(),
        mode,
        body,
        entries,
        omitted,
        eligible.size(),
        textTokens,
        budget.usage(textTokens + fixedTokens),
        warnings,
        new ProjectSnapshot(id, nowMillis, stamps, sentHashes));
  }

  // ---- overview ----

  /** What the overview costs and what is left for files. */
  private static final class Overview {
    int available;
    boolean compact;
    List<String> dependencyLines;
  }

  private static Overview overview(
      ProjectIndex index,
      ProjectReader reader,
      ContextMode mode,
      ScanOptions options,
      ContextBudget budget,
      int fixedTokens,
      int eligibleCount)
      throws AiException {
    Overview o = new Overview();
    o.dependencyLines = dependencies(index, reader, options);
    List<Omitted> worst = new ArrayList<>();
    for (ProjectFile f : index.files()) worst.add(new Omitted(f, Omitted.Why.NOT_SELECTED, "", 0));
    String head = header(index, mode, 0, eligibleCount, worst, o.dependencyLines);
    String fullTree = tree(index.files(), Set.of(), false);
    int window = budget.effectiveWindow();
    int fullTokens =
        ContextBudget.estimateTokens(head + "\nPROJECT STRUCTURE\n" + fullTree + "\nFILES\n");
    o.compact = ContextBudget.estimateTokens(fullTree) > Math.max(1_000, window / 5);
    String shown = o.compact ? tree(index.files(), Set.of(), true) : fullTree;
    int overviewTokens =
        o.compact
            ? ContextBudget.estimateTokens(head + "\nPROJECT STRUCTURE\n" + shown + "\nFILES\n")
            : fullTokens;
    o.available = budget.availableForFiles(fixedTokens + overviewTokens);
    int roomForOverview = budget.availableForFiles(fixedTokens);
    if (overviewTokens > roomForOverview)
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.OVERSIZED,
          "Even the project overview (about "
              + ContextBudget.format(overviewTokens)
              + " tokens) does not fit the "
              + ContextBudget.format(roomForOverview)
              + " tokens available. Raise ai.contextTokens, or exclude folders with"
              + " ai.project.exclude.");
    return o;
  }

  private static List<String> dependencies(
      ProjectIndex index, ProjectReader reader, ScanOptions options) {
    List<String> lines = new ArrayList<>();
    for (ProjectFile f : index.files()) {
      if (f.kind() != ProjectFile.Kind.BUILD || ProjectDependencies.systemOf(f.name()) == null)
        continue;
      try {
        String text = reader.read(f, options.maxFileBytes());
        for (String dep : ProjectDependencies.read(f.name(), text))
          if (lines.size() < MAX_DEPENDENCY_LINES)
            lines.add("  " + (f.directory().isEmpty() ? "" : f.directory() + ": ") + dep);
      } catch (IOException ex) {
        // an unreadable build file is simply not summarised; the files list says what was read
      }
    }
    return lines;
  }

  private static String header(
      ProjectIndex index,
      ContextMode mode,
      int included,
      int eligible,
      List<Omitted> omitted,
      List<String> dependencyLines) {
    StringBuilder out = new StringBuilder();
    out.append("PROJECT: ").append(index.name()).append('\n');
    out.append("Mode: ").append(mode.label()).append('\n');
    long notShown = omitted.size();
    if (notShown > 0 || included < eligible)
      out.append("Files of the project: ")
          .append(eligible)
          .append(", shown below: ")
          .append(included)
          .append(". Files marked (not included) in the structure are NOT shown to you.\n");
    else out.append("Files of the project: ").append(eligible).append(", all shown below.\n");
    Map<String, Long> languages = index.languageCounts();
    if (!languages.isEmpty()) {
      List<String> parts = new ArrayList<>();
      for (var e : languages.entrySet()) parts.add(e.getKey() + " " + e.getValue());
      out.append("Languages: ").append(String.join(", ", parts)).append('\n');
    }
    Set<String> systems = new LinkedHashSet<>();
    for (ProjectFile f : index.files()) {
      String system =
          f.kind() == ProjectFile.Kind.BUILD ? ProjectDependencies.systemOf(f.name()) : null;
      if (system != null) systems.add(system + " (" + f.path() + ")");
    }
    if (!systems.isEmpty()) out.append("Build: ").append(String.join(", ", systems)).append('\n');
    if (!dependencyLines.isEmpty()) {
      out.append("Declared dependencies:\n");
      for (String line : dependencyLines) out.append(line).append('\n');
    }
    return out.toString();
  }

  // ---- structure ----

  private static final Comparator<String> BY_SEGMENTS =
      (a, b) -> {
        String[] x = a.split("/");
        String[] y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
          int c = x[i].compareTo(y[i]);
          if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
      };

  /**
   * The folders and files, indented. A file that is not among {@code included} says so, so a model
   * never mistakes a missing file for a file that does not exist. {@code compact} lists folders
   * with their file counts instead of every file.
   */
  static String tree(List<ProjectFile> files, Set<String> included, boolean compact) {
    List<String> paths = new ArrayList<>();
    for (ProjectFile f : files) paths.add(f.path());
    paths.sort(BY_SEGMENTS);
    StringBuilder out = new StringBuilder();
    if (compact) {
      Map<String, Integer> perDirectory = new TreeMap<>(BY_SEGMENTS);
      for (String p : paths) {
        int slash = p.lastIndexOf('/');
        perDirectory.merge(slash < 0 ? "." : p.substring(0, slash), 1, Integer::sum);
      }
      for (var e : perDirectory.entrySet())
        out.append(e.getKey())
            .append("/ (")
            .append(e.getValue())
            .append(e.getValue() == 1 ? " file)\n" : " files)\n");
      return out.toString();
    }
    String[] open = new String[0];
    for (String p : paths) {
      String[] parts = p.split("/");
      int common = 0;
      while (common < open.length
          && common < parts.length - 1
          && open[common].equals(parts[common])) common++;
      for (int d = common; d < parts.length - 1; d++)
        out.append("  ".repeat(d)).append(parts[d]).append("/\n");
      out.append("  ".repeat(parts.length - 1)).append(parts[parts.length - 1]);
      if (!included.contains(p)) out.append(" (not included)");
      out.append('\n');
      open = Arrays.copyOf(parts, parts.length - 1);
    }
    return out.toString();
  }

  // ---- files ----

  /**
   * A run of {@code =} long enough that no file contains it next to the words that open or close a
   * file block, so a file's text can never fake the end of its own block.
   */
  static String marker(List<Loaded> files) {
    String marker = "=====";
    boolean clash = true;
    while (clash) {
      clash = false;
      for (Loaded l : files)
        if (l.text().contains(marker + " BEGIN FILE") || l.text().contains(marker + " END FILE")) {
          clash = true;
          break;
        }
      if (clash) marker += "=";
    }
    return marker;
  }

  private static String block(String marker, Loaded l) {
    StringBuilder out = new StringBuilder();
    out.append(marker)
        .append(" BEGIN FILE: ")
        .append(l.file().path())
        .append(" (")
        .append(l.file().language() == null ? "text" : l.file().language())
        .append(", ")
        .append(l.lines())
        .append(l.lines() == 1 ? " line) " : " lines) ")
        .append(marker)
        .append('\n');
    out.append(l.text());
    if (!l.text().isEmpty() && !l.text().endsWith("\n")) out.append('\n');
    out.append(marker)
        .append(" END FILE: ")
        .append(l.file().path())
        .append(' ')
        .append(marker)
        .append('\n');
    return out.toString();
  }

  private static int countLines(String text) {
    if (text.isEmpty()) return 0;
    int n = 0;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
    return text.endsWith("\n") ? n : n + 1;
  }

  private static String snapshotId(ContextMode mode, Map<String, String> hashes) {
    StringBuilder all = new StringBuilder(mode.name());
    for (var e : hashes.entrySet())
      all.append('\n').append(e.getKey()).append(':').append(e.getValue());
    return ProjectReader.hash(all.toString()).substring(0, 12);
  }

  private static String messageOf(IOException ex) {
    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
  }
}
