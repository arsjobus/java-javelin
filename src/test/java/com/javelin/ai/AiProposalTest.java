package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiProposalTest {
  private static AiProposal change(String doc, int a, int b, String code) throws Exception {
    AiContext c =
        AiContext.of(
            AiAction.PROPOSE_CHANGE, new AiSource("Foo.java", "Foo.java", "java", doc, a, b), 1000);
    return AiProposal.replace(c, code, "why");
  }

  @Test
  void applyingReplacesExactlyTheSelectedRange() throws Exception {
    String doc = "head\nold line\ntail\n";
    AiProposal p = change(doc, 5, 14, "new line");
    assertTrue(p.isStillValid(doc));
    assertEquals("head\nnew line\ntail\n", p.applyTo(doc));
  }

  @Test
  void aProposalIsNeverAppliedToTextThatChanged() throws Exception {
    String doc = "head\nold line\ntail\n";
    AiProposal p = change(doc, 5, 14, "new line");
    String edited = "head\nold lime\ntail\n";
    assertFalse(p.isStillValid(edited));
    assertThrows(IllegalStateException.class, () -> p.applyTo(edited));
    assertFalse(p.isStillValid("short"));
    String shifted = "X" + doc;
    assertFalse(p.isStillValid(shifted));
  }

  @Test
  void aNewDocumentIsAlwaysApplicableAndEndsInANewline() {
    AiProposal p = AiProposal.newDocument("FooTest.java", "class FooTest {}", "tests");
    assertEquals(AiProposal.Kind.NEW_DOCUMENT, p.kind());
    assertTrue(p.isStillValid("anything"));
    assertEquals("class FooTest {}\n", p.applyTo("anything"));
    assertEquals("FooTest.java", p.suggestedName());
  }

  @Test
  void theDiffAndSummaryDescribeTheChange() throws Exception {
    AiProposal p = change("a\nb\nc\n", 0, 6, "a\nB\nc");
    assertEquals(1, p.diff().added());
    assertEquals(1, p.diff().removed());
    assertEquals("Change to Foo.java: +1 -1", p.summary());
    assertEquals(
        "New file T.java: 2 line(s)", AiProposal.newDocument("T.java", "x\ny", "").summary());
  }

  @Test
  void aReplacementTakesTheLineEndingsOfTheTextItReplaces() throws Exception {
    String doc = "head\r\nold line\r\ntail\r\n";
    AiProposal p = change(doc, 6, 16, "new\nline");
    assertEquals("head\r\nnew\r\nline\r\ntail\r\n", p.applyTo(doc));
  }

  @Test
  void unixTextStaysUnixWhateverTheModelSent() throws Exception {
    String doc = "head\nold\ntail\n";
    assertEquals("head\nnew\nline\ntail\n", change(doc, 5, 9, "new\r\nline").applyTo(doc));
  }
}
