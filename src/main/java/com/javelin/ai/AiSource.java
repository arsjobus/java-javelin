package com.javelin.ai;

/**
 * The document an action starts from, as the window sees it: the text, the selection (equal start
 * and end mean none), and what the file is called. {@code fileName} is null for a document that has
 * never been saved.
 */
public record AiSource(
    String name,
    String fileName,
    String languageId,
    String text,
    int selectionStart,
    int selectionEnd) {
  public AiSource {
    int length = text.length();
    int a = Math.max(0, Math.min(selectionStart, length));
    int b = Math.max(0, Math.min(selectionEnd, length));
    selectionStart = Math.min(a, b);
    selectionEnd = Math.max(a, b);
  }

  public boolean hasSelection() {
    return selectionEnd > selectionStart;
  }
}
