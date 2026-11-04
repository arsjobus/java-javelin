package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UnifiedPatchTest {
  private static final String CODE = "one\ntwo\nthree\nfour\nfive\nsix\nseven\n";

  @Test
  void aHunkReplacesItsLines() throws Exception {
    String patch = "--- a/f.txt\n+++ b/f.txt\n@@ -2,3 +2,3 @@\n two\n-three\n+THREE\n four\n";
    assertEquals("one\ntwo\nTHREE\nfour\nfive\nsix\nseven\n", UnifiedPatch.apply(CODE, patch));
  }

  @Test
  void wrongLineNumbersAndCountsDoNotMatterBecauseTheContextFindsTheHunk() throws Exception {
    String patch = "@@ -90,40 +90,1 @@\n two\n-three\n+THREE\n four\n";
    assertEquals("one\ntwo\nTHREE\nfour\nfive\nsix\nseven\n", UnifiedPatch.apply(CODE, patch));
  }

  @Test
  void severalHunksApplyAndLaterOnesAccountForEarlierGrowth() throws Exception {
    String patch =
        "@@ -1,2 +1,3 @@\n one\n+one and a half\n two\n@@ -5,2 +6,2 @@\n five\n-six\n+SIX\n";
    assertEquals(
        "one\none and a half\ntwo\nthree\nfour\nfive\nSIX\nseven\n",
        UnifiedPatch.apply(CODE, patch));
  }

  @Test
  void aModelThatDropsTheSpaceInFrontOfContextLinesIsUnderstood() throws Exception {
    String patch = "@@ -2,3 +2,3 @@\ntwo\n-three\n+THREE\nfour\n";
    assertEquals("one\ntwo\nTHREE\nfour\nfive\nsix\nseven\n", UnifiedPatch.apply(CODE, patch));
  }

  @Test
  void whenTheSameContextAppearsTwiceTheLineNumberChoosesTheNearerOne() throws Exception {
    String text = "a\nx\nb\nc\na\nx\nb\nc\n";
    assertEquals(
        "a\nx\nb\nc\na\nY\nb\nc\n", UnifiedPatch.apply(text, "@@ -5,3 +5,3 @@\n a\n-x\n+Y\n b\n"));
    assertEquals(
        "a\nY\nb\nc\na\nx\nb\nc\n", UnifiedPatch.apply(text, "@@ -1,3 +1,3 @@\n a\n-x\n+Y\n b\n"));
  }

  @Test
  void lostIndentationInTheHunkIsRestored() throws Exception {
    String text = "void f() {\n    int a = 1;\n    int b = 2;\n}\n";
    assertEquals(
        "void f() {\n    int a = 1;\n    int b = 3;\n}\n",
        UnifiedPatch.apply(text, "@@ -2,2 +2,2 @@\n int a = 1;\n-int b = 2;\n+int b = 3;\n"));
  }

  @Test
  void aHunkThatDoesNotFitStopsEverythingAndNamesTheHunk() {
    String patch = "@@ -1,1 +1,1 @@\n-one\n+ONE\n@@ -3,2 +3,2 @@\n-nothing like this\n+x\n";
    AiException ex = assertThrows(AiException.class, () -> UnifiedPatch.apply(CODE, patch));
    assertTrue(ex.getMessage().contains("Hunk 2"));
  }

  @Test
  void aPatchWithoutHunksIsNotAPatch() {
    assertFalse(UnifiedPatch.looksLike("just text\n- a list item\n"));
    assertTrue(UnifiedPatch.looksLike("--- a\n+++ b\n@@ -1 +1 @@\n-a\n+b\n"));
    assertThrows(AiException.class, () -> UnifiedPatch.apply(CODE, "--- a\n+++ b\n"));
  }

  @Test
  void noNewlineMarkersAndGitHeadersAreIgnored() throws Exception {
    String patch =
        "diff --git a/f b/f\nindex 1..2 100644\n--- a/f\n+++ b/f\n@@ -6,2 +6,2 @@\n six\n-seven\n+SEVEN\n\\ No newline at end of file\n";
    assertEquals("one\ntwo\nthree\nfour\nfive\nsix\nSEVEN\n", UnifiedPatch.apply(CODE, patch));
  }
}
