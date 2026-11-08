package com.javelin.ai.verify;

/**
 * Where a selection-based proposal lands: the project-relative path of the document (forward
 * slashes) and its whole current text, so the change can be checked as the file it would become.
 */
public record VerifyTarget(String path, String fullText) {}
