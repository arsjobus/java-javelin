package com.javelin.ai.project;

import com.javelin.ai.symbols.OutlineSource;
import com.javelin.ai.symbols.SymbolBudget;

/**
 * Asks a project context to be built at symbol level (Phase 4): {@code request} is what the user
 * wants, so the planner can tell which methods it is about; {@code budget} is the profile's limits;
 * {@code outlines} says where the members of a file come from. Null for none of this, which is the
 * file-level context the editor has always built.
 */
public record SymbolOptions(String request, SymbolBudget budget, OutlineSource outlines) {
  public SymbolOptions {
    if (request == null) request = "";
    if (budget == null) budget = SymbolBudget.SMALL;
    if (outlines == null) outlines = OutlineSource.PATTERNS;
  }

  public SymbolOptions(String request, SymbolBudget budget) {
    this(request, budget, OutlineSource.PATTERNS);
  }
}
