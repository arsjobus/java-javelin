package com.javelin.ai.project;

import java.util.List;

/**
 * A file that is in the context, why, and what it costs. {@code hash} fingerprints its text. {@code
 * symbols} is empty for a file that is sent whole; for a file sent in part (symbol-level context,
 * Phase 4) it lists every symbol of the file and how each is shown.
 */
public record ContextEntry(
    ProjectFile file,
    String reason,
    int tokens,
    String hash,
    boolean fromUser,
    List<ShownSymbol> symbols) {
  public ContextEntry {
    symbols = List.copyOf(symbols);
  }

  public ContextEntry(ProjectFile file, String reason, int tokens, String hash, boolean fromUser) {
    this(file, reason, tokens, hash, fromUser, List.of());
  }

  /** True when only part of the file is sent. */
  public boolean isPartial() {
    return !symbols.isEmpty();
  }
}
