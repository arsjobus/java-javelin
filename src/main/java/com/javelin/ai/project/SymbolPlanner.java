package com.javelin.ai.project;

import com.javelin.ai.SourceOutline.Outline;
import com.javelin.ai.symbols.SymbolBudget;
import com.javelin.ai.symbols.SymbolIndex;
import com.javelin.ai.symbols.SymbolIndex.CallSite;
import com.javelin.ai.symbols.SymbolIndex.Symbol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Smart Context at symbol level (Phase 4). Given the Java files of a project and the request, it
 * finds the methods the request is about (their names match its words), then takes what a model
 * needs to change them and nothing else: the bodies of those methods, the signatures of the project
 * types they use, and their direct callers. Every other method of a file it shows is a signature.
 * Each step is limited by the profile's {@link SymbolBudget}, in order of importance, and what a
 * limit leaves out is said so. It reads the same project and request the same way every time: no
 * model, no clock, no randomness.
 *
 * <p>It decides what to show; {@link ContextBuilder} decides what fits the window. A file is never
 * shown in part unless it is large enough to gain from it and its outline is exact; otherwise it is
 * shown whole, as before.
 */
final class SymbolPlanner {
  private SymbolPlanner() {}

  /** A Java file the planner may use. {@code test} is the scanner's own classification. */
  record JavaSource(String path, String text, boolean test) {}

  /** How one file is shown. */
  record View(String path, String text, int originalLines, List<ShownSymbol> shown) {}

  /**
   * The views, the files to add because a target uses them or they call a target (path to reason),
   * and sentences about what the limits did.
   */
  record Plan(Map<String, View> views, Map<String, String> additions, List<String> notes) {
    static final Plan NONE = new Plan(Map.of(), Map.of(), List.of());
  }

  static final double MIN_TARGET_SCORE = 6.0;

  private record Candidate(Symbol symbol, double score, String reason, boolean qualified) {}

  static Plan plan(
      List<JavaSource> files, Set<String> picked, SymbolOptions options, ContextBudget estimator) {
    SymbolBudget limits = options.budget();
    Map<String, String> texts = new LinkedHashMap<>();
    Set<String> tests = new HashSet<>();
    for (JavaSource f : files) {
      texts.put(f.path(), f.text());
      if (f.test()) tests.add(f.path());
    }
    SymbolIndex index = SymbolIndex.build(texts, options.outlines());
    List<String> notes = new ArrayList<>();
    List<Vocabulary.Term> terms = Vocabulary.terms(options.request());
    Named named = Named.of(options.request());

    // 1. the methods the request is about
    List<Candidate> candidates = candidates(index, terms, named, tests);
    Map<String, Set<Integer>> full = new HashMap<>();
    Map<String, Map<Integer, String>> why = new HashMap<>();
    List<Symbol> targets = new ArrayList<>();
    int tokens = 0;
    int matched = candidates.size();
    for (Candidate c : candidates) {
      if (targets.size() >= limits.maxTargets()) break;
      String body = index.body(c.symbol());
      if (body.length() > limits.maxBodyChars()) {
        hold(
            why,
            c.symbol(),
            "target ("
                + c.reason()
                + "), but its body is "
                + body.length()
                + " characters, more than the "
                + limits.name()
                + " profile sends");
        continue;
      }
      int cost = estimator.estimate(body);
      if (!targets.isEmpty() && tokens + cost > limits.maxTokens()) {
        hold(why, c.symbol(), "target (" + c.reason() + "), but the symbol budget is used up");
        continue;
      }
      targets.add(c.symbol());
      tokens += cost;
      show(full, why, c.symbol(), "target: " + c.reason());
    }
    if (targets.isEmpty()) {
      notes.add(
          matched == 0
              ? "No method name matches the request, so the files are sent whole."
              : "The methods that match the request are too large for the "
                  + limits.name()
                  + " profile, so the files are sent whole.");
      return new Plan(Map.of(), Map.of(), notes);
    }
    if (matched > targets.size() && targets.size() >= limits.maxTargets())
      notes.add(
          (matched - targets.size())
              + " more method(s) match the request; the "
              + limits.name()
              + " profile sends "
              + limits.maxTargets()
              + " in full.");

    // 2. their direct callers
    Map<String, String> additions = new TreeMap<>();
    Set<Symbol> callers = new java.util.LinkedHashSet<>();
    for (Symbol target : targets) {
      for (CallSite site : index.callSitesOf(target)) {
        Symbol caller = site.caller();
        if (targets.contains(caller) || callers.contains(caller)) continue;
        if (callers.size() >= limits.maxCallers()) {
          notes.add("More callers exist than the " + limits.name() + " profile sends.");
          break;
        }
        callers.add(caller);
        String how = "calls " + target.qualified() + " (line " + site.line() + ", found by name)";
        String body = index.body(caller);
        if (body.length() > limits.maxCallerBodyChars()) {
          hold(
              why,
              caller,
              how
                  + "; its body is "
                  + body.length()
                  + " characters, more than the "
                  + limits.name()
                  + " profile sends");
        } else if (tokens + estimator.estimate(body) > limits.maxTokens()) {
          hold(why, caller, how + "; the symbol budget is used up");
        } else {
          tokens += estimator.estimate(body);
          show(full, why, caller, how);
        }
        if (!picked.contains(caller.path()))
          additions.putIfAbsent(caller.path(), "calls " + target.qualified());
      }
    }

    // 3. the types the targets use
    Map<String, String> typeWhy = new LinkedHashMap<>();
    for (Symbol target : targets)
      for (Symbol type : index.typesUsedBy(target)) {
        if (typeWhy.size() >= limits.maxUsedTypes()) break;
        typeWhy.putIfAbsent(type.path(), "type used by " + target.qualified());
        why.computeIfAbsent(type.path(), k -> new HashMap<>())
            .putIfAbsent(type.member().first(), "type used by " + target.qualified());
      }
    for (var e : typeWhy.entrySet())
      if (!picked.contains(e.getKey())) additions.putIfAbsent(e.getKey(), e.getValue());

    // 4. how each file is shown
    Map<String, View> views = new LinkedHashMap<>();
    Set<String> concerned = new java.util.LinkedHashSet<>(picked);
    concerned.addAll(additions.keySet());
    List<String> dropped = new ArrayList<>();
    List<String> inexact = new ArrayList<>();
    for (String path : concerned) {
      if (!texts.containsKey(path)) continue;
      String text = texts.get(path);
      Outline outline = index.outline(path);
      int wholeTokens = estimator.estimate(text);
      if (wholeTokens <= limits.wholeFileTokens()) continue;
      if (outline == null || !outline.isComplete()) {
        inexact.add(path);
        continue;
      }
      SymbolSkeleton.Rendered view =
          SymbolSkeleton.render(
              text,
              outline,
              full.getOrDefault(path, Set.of()),
              why.getOrDefault(path, Map.of()),
              limits.maxSignaturesPerType());
      int viewTokens = estimator.estimate(view.text());
      if (viewTokens >= wholeTokens * 0.9)
        continue; // a view that saves almost nothing is not worth it
      if (!picked.contains(path) && tokens + viewTokens > limits.maxTokens()) {
        dropped.add(path);
        additions.remove(path);
        continue;
      }
      if (!picked.contains(path)) tokens += viewTokens;
      views.put(path, new View(path, view.text(), countLines(text), view.shown()));
    }
    if (!inexact.isEmpty())
      notes.add(
          inexact.size()
              + " large file(s) are sent whole because their layout is not one the outline can"
              + " read exactly: "
              + String.join(", ", inexact.subList(0, Math.min(3, inexact.size())))
              + (inexact.size() > 3 ? " and " + (inexact.size() - 3) + " more." : "."));
    for (String path : dropped)
      notes.add(path + " is left out: its signatures do not fit the symbol budget.");
    // an added file that is small enough to be sent whole counts against the budget too
    for (String path : new ArrayList<>(additions.keySet())) {
      if (views.containsKey(path) || !texts.containsKey(path)) continue;
      int cost = estimator.estimate(texts.get(path));
      if (tokens + cost > limits.maxTokens()) {
        additions.remove(path);
        notes.add(path + " is left out: it does not fit the symbol budget.");
      } else tokens += cost;
    }
    return new Plan(views, additions, notes);
  }

  // ---- finding the methods a request is about ----

  /**
   * What the request spells out as identifiers. A method written the way code writes it ({@code
   * ProjectReader.hash}, {@code applyDiscount}) is a much stronger sign than a word of the request
   * that happens to be part of some method's name.
   */
  record Named(Set<String> identifiers, Set<String> qualified, boolean constructors) {
    private static final java.util.regex.Pattern IDENT =
        java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final java.util.regex.Pattern DOTTED =
        java.util.regex.Pattern.compile("([A-Za-z_$][\\w$]*)\\.([A-Za-z_$][\\w$]*)");

    static Named of(String request) {
      Set<String> identifiers = new java.util.LinkedHashSet<>();
      var m = IDENT.matcher(request);
      while (m.find()) identifiers.add(m.group());
      Set<String> qualified = new java.util.LinkedHashSet<>();
      var d = DOTTED.matcher(request);
      while (d.find()) qualified.add(d.group(1) + "." + d.group(2));
      String lower = request.toLowerCase(java.util.Locale.ROOT);
      return new Named(
          identifiers, qualified, lower.contains("constructor") || lower.contains("new "));
    }
  }

  private static String lastSegment(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  private static List<Candidate> candidates(
      SymbolIndex index, List<Vocabulary.Term> terms, Named named, Set<String> tests) {
    List<Candidate> out = new ArrayList<>();
    for (Symbol s : index.symbols()) {
      if (!s.isCallable() || !s.member().hasBody() || tests.contains(s.path())) continue;
      // a constructor is called by the name of its type, which matches every word of the type's
      // name: it is only a target when the request is about constructors
      if (s.kind() == com.javelin.ai.SourceOutline.Kind.CONSTRUCTOR && !named.constructors())
        continue;
      List<String> nameWords = stems(Vocabulary.words(s.name()));
      List<String> ownerWords = stems(Vocabulary.words(s.member().owner()));
      Map<String, Integer> bodyWords = new HashMap<>();
      for (String w : Vocabulary.words(index.body(s)))
        bodyWords.merge(Vocabulary.stem(w), 1, Integer::sum);
      double name = 0;
      double rest = 0;
      List<String> hits = new ArrayList<>();
      for (Vocabulary.Term t : terms) {
        if (anyRelated(nameWords, t.word())) {
          name += 6 * t.weight();
          if (t.weight() == 1.0 && hits.size() < 3) hits.add(t.word());
        } else if (anyRelated(ownerWords, t.word())) rest += 2 * t.weight();
        int count = 0;
        for (var e : bodyWords.entrySet())
          if (Vocabulary.related(e.getKey(), t.word())) count += e.getValue();
        rest += Math.min(count, 8) * 0.5 * t.weight();
      }
      String owner = lastSegment(s.member().owner());
      String reason =
          hits.isEmpty()
              ? "its name is related to the request"
              : "name matches: " + String.join(", ", hits);
      boolean qualified = named.qualified().contains(owner + "." + s.name());
      if (qualified) {
        name += 30;
        reason = "named in the request as " + owner + "." + s.name();
      } else if (named.identifiers().contains(s.name())) {
        name += 12;
        reason = "named in the request: " + s.name();
      }
      if (named.identifiers().contains(owner)) rest += 3;
      if (name < MIN_TARGET_SCORE) continue;
      out.add(new Candidate(s, name + rest, reason, qualified));
    }
    // a request that writes Owner.method means that method: the words of the rest of the sentence
    // ("files", "line") must not pull in every method that happens to be called that
    if (out.stream().anyMatch(Candidate::qualified)) out.removeIf(c -> !c.qualified());
    out.sort(
        Comparator.comparingDouble((Candidate c) -> -c.score())
            .thenComparing(c -> c.symbol().path())
            .thenComparingInt(c -> c.symbol().member().first()));
    return out;
  }

  private static void show(
      Map<String, Set<Integer>> full,
      Map<String, Map<Integer, String>> why,
      Symbol s,
      String reason) {
    full.computeIfAbsent(s.path(), k -> new HashSet<>()).add(s.member().first());
    why.computeIfAbsent(s.path(), k -> new HashMap<>()).put(s.member().first(), reason);
  }

  private static void hold(Map<String, Map<Integer, String>> why, Symbol s, String reason) {
    why.computeIfAbsent(s.path(), k -> new HashMap<>()).put(s.member().first(), reason);
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

  static int countLines(String text) {
    if (text.isEmpty()) return 0;
    int n = 0;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
    return text.endsWith("\n") ? n : n + 1;
  }
}
