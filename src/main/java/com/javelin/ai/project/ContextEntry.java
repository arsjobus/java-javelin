package com.javelin.ai.project;

/** A file that is in the context, why, and what it costs. {@code hash} fingerprints its text. */
public record ContextEntry(
    ProjectFile file, String reason, int tokens, String hash, boolean fromUser) {}
