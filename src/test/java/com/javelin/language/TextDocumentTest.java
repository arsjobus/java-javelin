package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class TextDocumentTest {
  private final TextDocument doc =
      new TextDocument(URI.create("file:///a.java"), "java", "ab\ncde\n\nf");

  @Test
  void basics() {
    assertEquals(URI.create("file:///a.java"), doc.uri());
    assertEquals("java", doc.languageId());
    assertEquals(1, doc.version());
    assertEquals("ab\ncde\n\nf", doc.text());
    assertTrue(doc.matches("ab\ncde\n\nf"));
    assertFalse(doc.matches("ab\ncde\n\ng"));
    assertFalse(doc.matches("ab"));
  }

  @Test
  void positionsAreLineAndColumnAndClamped() {
    assertEquals(new Lsp.Position(0, 0), doc.positionAt(0));
    assertEquals(new Lsp.Position(0, 2), doc.positionAt(2));
    assertEquals(new Lsp.Position(1, 0), doc.positionAt(3));
    assertEquals(new Lsp.Position(1, 3), doc.positionAt(6));
    assertEquals(new Lsp.Position(2, 0), doc.positionAt(7));
    assertEquals(new Lsp.Position(3, 1), doc.positionAt(9));
    assertEquals(new Lsp.Position(0, 0), doc.positionAt(-5));
    assertEquals(new Lsp.Position(3, 1), doc.positionAt(10_000));
  }

  @Test
  void replaceReportsTheOldRangeAndBumpsTheVersion() {
    Lsp.Range range = doc.replace(1, 4, "X");
    assertEquals(new Lsp.Range(new Lsp.Position(0, 1), new Lsp.Position(1, 2)), range);
    assertEquals("aXe\n\nf", doc.text());
    assertEquals(2, doc.version());
  }

  @Test
  void replaceClampsOutOfRangeArguments() {
    assertEquals(
        new Lsp.Range(new Lsp.Position(0, 0), new Lsp.Position(0, 0)), doc.replace(-3, -1, ">"));
    assertEquals(">ab\ncde\n\nf", doc.text());
    doc.replace(500, 500, "!");
    assertEquals(">ab\ncde\n\nf!", doc.text());
    doc.replace(0, 10_000, "");
    assertEquals("", doc.text());
  }

  @Test
  void resetReplacesEverything() {
    doc.reset("new");
    assertEquals("new", doc.text());
    assertEquals(2, doc.version());
  }

  @Test
  void utf16ColumnsMatchJavaOffsets() {
    TextDocument emoji = new TextDocument(URI.create("file:///e"), "java", "\uD83D\uDE00x");
    assertEquals(new Lsp.Position(0, 3), emoji.positionAt(3));
  }

  @Test
  void largeDocumentsAreTrackedQuickly() {
    String line = "int value = compute(a, b, c);\n";
    TextDocument big = new TextDocument(URI.create("file:///big"), "java", line.repeat(200_000));
    long start = System.nanoTime();
    for (int i = 0; i < 200; i++)
      big.replace(i * line.length(), 1, "I"); // line starts, so the line count is unchanged
    assertEquals(new Lsp.Position(199_999, 29), big.positionAt(big.text().length() - 1));
    assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
  }
}
