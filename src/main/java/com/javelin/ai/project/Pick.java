package com.javelin.ai.project;

/**
 * A selector's choice of one file: {@code reason} says in words why it is wanted and {@code score}
 * orders the picks (higher first; ties go by path so the order never depends on chance).
 */
public record Pick(String path, String reason, double score) {}
