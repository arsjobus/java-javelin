package com.javelin.ai.project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Smart Context: picks the files that look relevant to a request, each with a reason a person can
 * read, using only names, paths, declarations, imports, documents and the usual naming of tests. No
 * model and no index on disk: the same project and request always give the same picks, in the same
 * order. It finds a starting set by matching the request's words, then follows imports to what
 * those files use and what uses them, adds their tests, and always includes the project's overview
 * documents and build configuration.
 */
public final class SmartSelector {
  private SmartSelector() {}

  static final int MAX_SEEDS = 12;
  static final double MIN_SEED_SCORE = 6.0;
  static final int MAX_DEPENDENTS_PER_SEED = 4;
  static final int MAX_SIBLINGS = 3;
  static final int MAX_BASELINE_DOCS = 6;

  private static final class Scored {
    final ProjectFile file;
    double score;
    boolean fixed = true; // true while every reason for it is the standing overview
    final List<String> reasons = new ArrayList<>();

    Scored(ProjectFile file) {
      this.file = file;
    }

    void add(double points, String reason) {
      score = Math.max(score, points);
      if (!reasons.contains(reason) && reasons.size() < 4) reasons.add(reason);
    }
  }

  public static List<Pick> select(
      ProjectIndex index, ProjectReader reader, String request, ScanOptions options) {
    List<Vocabulary.Term> terms = Vocabulary.terms(request);
    Map<String, CodeFacts> facts = new TreeMap<>();
    for (ProjectFile f : index.files()) {
      try {
        facts.put(f.path(), new CodeFacts(f, reader.read(f, options.maxFileBytes())));
      } catch (IOException ex) {
        // a file that cannot be read cannot be ranked; the builder reports it if it is wanted
      }
    }
    CodeIndex codeIndex = new CodeIndex(facts.values());
    Map<String, Set<String>> dependsOn = new TreeMap<>();
    Map<String, Set<String>> usedBy = new TreeMap<>();
    for (CodeFacts f : facts.values()) {
      Set<String> deps = f.dependsOn(codeIndex);
      dependsOn.put(f.file.path(), deps);
      for (String d : deps)
        usedBy.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(f.file.path());
    }

    Map<String, Scored> chosen = new LinkedHashMap<>();
    List<Scored> direct = new ArrayList<>();

    // Ensure AGENTS.md and CLAUDE.md are always included at the beginning
    for (ProjectFile f : index.files()) {
      String name = f.name().toLowerCase(Locale.ROOT);
      if (name.equals("agents.md") || name.equals("claude.md")) {
        Scored s = new Scored(f);
        s.score = 10.0; // Highest priority, unconditional inclusion
        s.reasons.add("always included for agent instructions and model guidance");
        merge(chosen, f, s.score, s.reasons, true);
      }
    }

    for (CodeFacts f : facts.values()) {
      Scored s = score(f, terms);
      if (s.score > 0) direct.add(s);
    }
    direct.sort(
        Comparator.comparingDouble((Scored s) -> -s.score).thenComparing(s -> s.file.path()));

    List<Scored> seeds = new ArrayList<>();
    for (Scored s : direct) if (s.score >= MIN_SEED_SCORE && seeds.size() < MAX_SEEDS) seeds.add(s);
    if (seeds.isEmpty()) for (Scored s : direct) if (seeds.size() < 5) seeds.add(s);
    for (Scored s : seeds) merge(chosen, s.file, s.score, s.reasons);

    // what the starting files use, what uses them, and their neighbours in the same package
    int rank = 0;
    for (Scored seed : seeds) {
      String path = seed.file.path();
      for (String dep : dependsOn.getOrDefault(path, Set.of()))
        merge(chosen, index.find(dep), seed.score * 0.5, List.of("used by " + path));
      int dependents = 0;
      for (String user : usedBy.getOrDefault(path, Set.of())) {
        if (dependents++ >= MAX_DEPENDENTS_PER_SEED) break;
        merge(chosen, index.find(user), seed.score * 0.35, List.of("uses " + path));
      }
      if (rank++ < 3) siblings(index, facts, chosen, seed);
    }

    // tests of everything chosen so far
    List<ProjectFile> sources = new ArrayList<>();
    for (Scored s : chosen.values())
      if (s.file.kind() != ProjectFile.Kind.TEST) sources.add(s.file);
    for (ProjectFile source : sources) {
      double base = chosen.get(source.path()).score * 0.6;
      for (ProjectFile test : testsOf(index, facts, dependsOn, source))
        merge(chosen, test, base, List.of("test of " + source.path()));
    }

    // documents that talk about the request, then the standing overview and build files
    int docs = 0;
    for (Scored s : direct) {
      if (s.file.kind() == ProjectFile.Kind.DOC && s.score >= 3 && docs++ < 3)
        merge(chosen, s.file, s.score, s.reasons);
    }
    int baseline = 0;
    for (ProjectFile f : index.files()) {
      if (FullProjectSelector.isOverviewDocument(f) && baseline++ < MAX_BASELINE_DOCS)
        merge(
            chosen, f, 5.0, List.of("project documentation (architecture and conventions)"), true);
      else if (f.kind() == ProjectFile.Kind.BUILD
          && f.directory().isEmpty()
          && ProjectDependencies.systemOf(f.name()) != null)
        merge(chosen, f, 4.0, List.of("build configuration"), true);
    }

    List<Pick> picks = new ArrayList<>();
    chosen.values().stream()
        .sorted(
            Comparator.comparingDouble((Scored s) -> -rank(s)).thenComparing(s -> s.file.path()))
        .forEach(s -> picks.add(new Pick(s.file.path(), String.join("; ", s.reasons), rank(s))));
    return picks;
  }

  private static void merge(
      Map<String, Scored> chosen, ProjectFile file, double score, List<String> reasons) {
    merge(chosen, file, score, reasons, false);
  }

  private static void merge(
      Map<String, Scored> chosen,
      ProjectFile file,
      double score,
      List<String> reasons,
      boolean standing) {
    if (file == null) return;
    Scored s = chosen.computeIfAbsent(file.path(), k -> new Scored(file));
    if (!standing) s.fixed = false;
    for (String reason : reasons) s.add(score, reason);
  }

  /**
   * What a file is worth against the room it takes: relevance, softened for big files so that one
   * large file that merely mentions the request's words does not push out several small files the
   * answer depends on. The standing overview documents keep their plain score.
   */
  static double rank(Scored s) {
    if (s.fixed) return s.score;
    double tokens = Math.ceil(s.file.size() / ContextBudget.CHARS_PER_TOKEN);
    return s.score / (1.0 + tokens / SIZE_SOFTENING_TOKENS);
  }

  /** A file of this many tokens counts for half of its relevance. */
  static final double SIZE_SOFTENING_TOKENS = 4_000;

  private static void siblings(
      ProjectIndex index, Map<String, CodeFacts> facts, Map<String, Scored> chosen, Scored seed) {
    CodeFacts own = facts.get(seed.file.path());
    if (own == null || own.packageName.isEmpty()) return;
    int added = 0;
    for (CodeFacts other : facts.values()) {
      if (added >= MAX_SIBLINGS) break;
      if (other.file.path().equals(seed.file.path())
          || !other.packageName.equals(own.packageName)
          || other.file.kind() == ProjectFile.Kind.TEST
          || chosen.containsKey(other.file.path())) continue;
      merge(chosen, other.file, 2.0, List.of("same package as " + seed.file.path()));
      added++;
    }
  }

  /** Test files named for {@code source} by the usual conventions, or that use it. */
  private static List<ProjectFile> testsOf(
      ProjectIndex index,
      Map<String, CodeFacts> facts,
      Map<String, Set<String>> dependsOn,
      ProjectFile source) {
    List<ProjectFile> tests = new ArrayList<>();
    String base = baseOf(source.name()).toLowerCase(Locale.ROOT);
    for (ProjectFile f : index.files()) {
      if (f.kind() != ProjectFile.Kind.TEST) continue;
      if (!java.util.Objects.equals(f.language(), source.language())) continue;
      String testBase = baseOf(f.name()).toLowerCase(Locale.ROOT);
      boolean named =
          testBase.equals(base + "test")
              || testBase.equals(base + "tests")
              || testBase.equals(base + "it")
              || testBase.equals("test" + base)
              || testBase.equals("test_" + base)
              || testBase.equals(base + "_test")
              || testBase.equals(base + ".test")
              || testBase.equals(base + ".spec");
      boolean uses = dependsOn.getOrDefault(f.path(), Set.of()).contains(source.path());
      if ((named || uses) && facts.containsKey(f.path())) tests.add(f);
    }
    return tests;
  }

  private static String baseOf(String name) {
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    if (base.endsWith(".test") || base.endsWith(".spec")) return base;
    return base;
  }

  // ---- scoring one file against the request ----

  private static Scored score(CodeFacts f, List<Vocabulary.Term> terms) {
    Scored s = new Scored(f.file);
    List<String> nameWords = stems(Vocabulary.words(f.baseName()));
    List<String> folderWords = stems(Vocabulary.words(f.file.directory()));
    List<String> nameHits = new ArrayList<>();
    List<String> folderHits = new ArrayList<>();
    List<String> declHits = new ArrayList<>();
    List<String> relatedHits = new ArrayList<>();
    Map<String, Integer> contentHits = new LinkedHashMap<>();
    double total = 0;
    for (Vocabulary.Term t : terms) {
      double w = t.weight();
      if (anyRelated(nameWords, t.word())) {
        total += 8 * w;
        if (w == 1.0) nameHits.add(t.word());
        else if (!relatedHits.contains(t.word())) relatedHits.add(t.word());
      } else if (anyRelated(folderWords, t.word())) {
        total += 4 * w;
        if (w == 1.0) folderHits.add(t.word());
        else if (!relatedHits.contains(t.word())) relatedHits.add(t.word());
      }
      for (String declared : f.declared)
        if (anyRelated(stems(Vocabulary.words(declared)), t.word())) {
          total += 6 * w;
          if (w == 1.0 && !declHits.contains(declared) && declHits.size() < 3)
            declHits.add(declared);
          break;
        }
      int hits = 0;
      for (Map.Entry<String, Integer> e : f.wordFrequency.entrySet())
        if (Vocabulary.related(e.getKey(), t.word())) hits += e.getValue();
      if (hits > 0) {
        total += Math.min(hits, 8) * 0.5 * w;
        if (w == 1.0) contentHits.put(t.word(), hits);
      }
    }
    if (f.file.kind() == ProjectFile.Kind.DOC) total *= 0.6;
    if (total <= 0) return s;
    List<String> reasons = new ArrayList<>();
    if (!nameHits.isEmpty()) reasons.add("file name matches: " + String.join(", ", nameHits));
    if (!folderHits.isEmpty()) reasons.add("folder matches: " + String.join(", ", folderHits));
    if (!declHits.isEmpty()) reasons.add("declares " + String.join(", ", declHits));
    if (!contentHits.isEmpty()) {
      List<String> parts = new ArrayList<>();
      for (var e : contentHits.entrySet()) parts.add(e.getKey() + " (" + e.getValue() + "x)");
      reasons.add("mentions " + String.join(", ", parts));
    }
    if (!relatedHits.isEmpty() && nameHits.isEmpty() && folderHits.isEmpty())
      reasons.add("name is related to: " + String.join(", ", relatedHits));
    if (reasons.isEmpty()) reasons.add("related words in its code");
    s.score = total;
    s.reasons.addAll(reasons);
    return s;
  }

  private static List<String> stems(List<String> words) {
    List<String> out = new ArrayList<>();
    for (String w : words) out.add(Vocabulary.stem(w));
    return out;
  }

  private static boolean anyRelated(List<String> words, String term) {
    for (String w : words) if (Vocabulary.related(w, term)) return true;
    return false;
  }
}
