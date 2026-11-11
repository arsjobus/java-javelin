package com.javelin.ai.symbols;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How much code one request may take from the symbol index, per kind of model. Each field is a
 * limit, not a target: the planner takes things in order of how much they matter (the methods to
 * change, then the signatures of the types they use, then their callers) and leaves out what the
 * limits do not allow, saying so.
 *
 * <p>Until the model profiles of Phase 5 exist, the two presets here are the {@code small} and
 * {@code frontier} columns of ROADMAP-AI-RELIABILITY.md's profile table, chosen by the {@code
 * ai.symbols.profile} setting. {@code frontier} has not been measured against any frontier model:
 * it is the same code with larger limits.
 *
 * @param maxTargets the most methods whose body is sent because they are what the request is about
 * @param maxBodyChars the longest method body that is sent
 * @param maxUsedTypes the most project types whose signatures are listed for the targets
 * @param maxSignaturesPerType the most member lines listed for one such type
 * @param maxCallers the most callers sent
 * @param maxCallerBodyChars the longest caller body that is sent; a longer caller is listed by its
 *     signature only
 * @param maxTokens what everything chosen at symbol level may cost, estimated
 * @param wholeFileTokens a file this small is sent whole: a view of a small file saves nothing
 */
public record SymbolBudget(
    String name,
    int maxTargets,
    int maxBodyChars,
    int maxUsedTypes,
    int maxSignaturesPerType,
    int maxCallers,
    int maxCallerBodyChars,
    int maxTokens,
    int wholeFileTokens) {
  /** Qwen-3.5-9B-class models: a few bodies, short lists, a tight total. */
  public static final SymbolBudget SMALL =
      new SymbolBudget("small", 3, 4_000, 6, 30, 6, 1_500, 4_000, 400);

  /** Larger models: more of everything. Not measured; see the class comment. */
  public static final SymbolBudget FRONTIER =
      new SymbolBudget("frontier", 8, 12_000, 20, 80, 20, 6_000, 20_000, 2_500);

  public static final List<SymbolBudget> PRESETS = List.of(SMALL, FRONTIER);

  /** The preset called {@code name} (any case), or empty. */
  public static Optional<SymbolBudget> named(String name) {
    if (name == null) return Optional.empty();
    String wanted = name.strip().toLowerCase(Locale.ROOT);
    return PRESETS.stream().filter(p -> p.name().equals(wanted)).findFirst();
  }
}
