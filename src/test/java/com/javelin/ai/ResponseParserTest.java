package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResponseParserTest {
  @Test
  void theFirstFencedBlockIsTheCode() throws Exception {
    var p =
        ResponseParser.parse("Here you go.\n```java\nint a = 1;\nint b = 2;\n```\nThat is all.");
    assertEquals("int a = 1;\nint b = 2;", p.code());
    assertEquals("Here you go.\n\nThat is all.", p.explanation());
    assertEquals(0, p.extraBlocks());
  }

  @Test
  void windowsLineEndingsAreNormalised() throws Exception {
    assertEquals("a\nb", ResponseParser.parse("x\r\n```\r\na\r\nb\r\n```\r\n").code());
  }

  @Test
  void aLongerFenceMayContainAShorterOne() throws Exception {
    assertEquals(
        "before\n```\ninner\n```\nafter",
        ResponseParser.parse("````md\nbefore\n```\ninner\n```\nafter\n````").code());
  }

  @Test
  void tildeFencesAndIndentedFencesWork() throws Exception {
    assertEquals("x", ResponseParser.parse("~~~\nx\n~~~").code());
    assertEquals("x", ResponseParser.parse("  ```py\nx\n  ```").code());
  }

  @Test
  void furtherBlocksAreCountedAndIgnored() throws Exception {
    var p = ResponseParser.parse("```\none\n```\ntext\n```sh\ntwo\n```\n~~~\nthree\n~~~");
    assertEquals("one", p.code());
    assertEquals(2, p.extraBlocks());
  }

  @Test
  void aReplyWithoutACodeBlockIsNotACodeReply() {
    for (String reply :
        new String[] {
          "I cannot do that.",
          "",
          "use ```inline``` code",
          "    ```\n    indented four spaces\n    ```"
        }) {
      AiException ex = assertThrows(AiException.class, () -> ResponseParser.parse(reply), reply);
      assertEquals(AiException.Kind.BAD_RESPONSE, ex.kind());
    }
  }

  @Test
  void aBlockThatNeverClosesIsRefused() {
    AiException ex =
        assertThrows(
            AiException.class, () -> ResponseParser.parse("Sure:\n```java\nint a = 1;\nint b"));
    assertTrue(ex.getMessage().contains("stops inside"));
  }

  @Test
  void anEmptyBlockIsRefused() {
    assertThrows(AiException.class, () -> ResponseParser.parse("```\n\n  \n```"));
  }

  @Test
  void aShorterClosingFenceDoesNotCloseALongerOne() {
    assertThrows(AiException.class, () -> ResponseParser.parse("````\ncode\n```"));
  }

  @Test
  void trailingNewlineFollowsTheOriginal() {
    assertEquals("b\n", ResponseParser.matchTrailingNewline("a\n", "b"));
    assertEquals("b\n", ResponseParser.matchTrailingNewline("a\n", "b\n"));
    assertEquals("b", ResponseParser.matchTrailingNewline("a", "b\n"));
    assertEquals("b", ResponseParser.matchTrailingNewline("a", "b"));
  }
}
