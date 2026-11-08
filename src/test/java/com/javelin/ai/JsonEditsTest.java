package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.language.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonEditsTest {
  private static String edit(String file, String anchor, String old, String neu) {
    return Json.write(Json.obj("file", file, "anchor", anchor, "old", old, "new", neu));
  }

  private static String reply(String explanation, String... edits) {
    return "{\"explanation\":"
        + Json.write(explanation)
        + ",\"edits\":["
        + String.join(",", edits)
        + "]}";
  }

  private static AiException failure(String reply) {
    return assertThrows(AiException.class, () -> JsonEdits.parse(reply));
  }

  @Test
  void aReplyWithEditsIsRead() throws Exception {
    String text =
        reply(
            "Fixed the sign.",
            edit("Foo.java", "int add(int a, int b) {", "  return a - b;", "  return a + b;"));
    assertTrue(JsonEdits.looksLike(text));
    JsonEdits.Parsed p = JsonEdits.parse(text);
    assertEquals("Fixed the sign.", p.explanation());
    assertEquals(1, p.edits().size());
    JsonEdits.FileEdit e = p.edits().get(0);
    assertEquals("Foo.java", e.file());
    assertEquals("int add(int a, int b) {", e.edit().anchor());
    assertEquals("  return a - b;", e.edit().search());
    assertEquals("  return a + b;", e.edit().replace());
  }

  @Test
  void textInsideTheStringsKeepsItsNewlinesQuotesAndBackslashes() throws Exception {
    String old = "  String s = \"a\\n\";\n  int x;";
    String neu = "  String s = \"b\\t\";\n  int y; // \"q\"";
    JsonEdits.Parsed p = JsonEdits.parse(reply("", edit("A.java", "", old, neu)));
    assertEquals(old, p.edits().get(0).edit().search());
    assertEquals(neu, p.edits().get(0).edit().replace());
  }

  @Test
  void aCodeFenceAroundTheJsonIsAccepted() throws Exception {
    String body = reply("x", edit("A.java", "", "a", "b"));
    for (String fenced :
        List.of("```json\n" + body + "\n```", "```\n" + body + "\n```", "  " + body + "\n")) {
      assertTrue(JsonEdits.looksLike(fenced), fenced);
      assertEquals(1, JsonEdits.parse(fenced).edits().size());
    }
  }

  @Test
  void reasoningInFrontOfTheJsonIsLeftOut() throws Exception {
    String text =
        "<think>maybe {\"edits\": []}</think>\n" + reply("", edit("A.java", "", "a", "b"));
    assertEquals(1, JsonEdits.parse(text).edits().size());
  }

  @Test
  void proseInFrontOfTheJsonIsNotTreatedAsJsonEdits() {
    String text = "Here you go:\n" + reply("", edit("A.java", "", "a", "b"));
    assertFalse(JsonEdits.looksLike(text));
  }

  @Test
  void aCodeBlockOfJavaThatMentionsEditsIsNotJsonEdits() {
    String text = "```java\nclass A { String s = \"edits\"; }\n```";
    assertFalse(JsonEdits.looksLike(text));
  }

  @Test
  void noEditsIsAnAnswerInWordsNotAChange() {
    String none = reply("This cannot be done without changing the API.");
    assertFalse(JsonEdits.looksLike(none));
    AiException ex = failure(none);
    assertEquals(FailureReason.NO_EDIT_BLOCK, ex.reason());
  }

  @Test
  void aReplyCutOffInTheMiddleOfTheJsonIsTruncatedAndProposesNothing() {
    String whole = reply("x", edit("A.java", "", "return a - b;", "return a + b;"));
    String cut = whole.substring(0, whole.length() - 12);
    assertTrue(JsonEdits.looksLike(cut));
    assertEquals(FailureReason.TRUNCATED, failure(cut).reason());
  }

  @Test
  void jsonThatIsBrokenButCompleteIsUnparseable() {
    String broken =
        "{\"explanation\":\"x\",\"edits\":[{\"file\":\"A.java\" \"old\":\"a\",\"new\":\"b\"}]}";
    assertTrue(JsonEdits.looksLike(broken));
    assertEquals(FailureReason.UNPARSEABLE, failure(broken).reason());
  }

  @Test
  void editsThatAreNotTheRightShapeAreUnparseable() {
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"explanation\":\"x\",\"edits\":{\"file\":\"a\"}}").reason());
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"explanation\":\"x\",\"edits\":[\"nope\"]}").reason());
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"edits\":[{\"file\":\"A.java\",\"old\":\"a\"}]}").reason(),
        "no new");
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"edits\":[{\"file\":\"A.java\",\"new\":\"b\"}]}").reason(),
        "no old");
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"edits\":[{\"old\":\"a\",\"new\":\"b\"}]}").reason(),
        "no file");
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure(reply("x", edit("  ", "", "a", "b"))).reason(),
        "blank file");
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"edits\":[{\"file\":\"A.java\",\"anchor\":5,\"old\":\"a\",\"new\":\"b\"}]}")
            .reason(),
        "anchor that is not text");
    assertEquals(
        FailureReason.UNPARSEABLE,
        failure("{\"edits\":[{\"file\":\"A.java\",\"old\":1,\"new\":\"b\"}]}").reason(),
        "old that is not text");
  }

  @Test
  void aMissingAnchorMeansNoAnchorAndAnEmptyNewMeansDeleting() throws Exception {
    JsonEdits.Parsed p =
        JsonEdits.parse("{\"edits\":[{\"file\":\"A.java\",\"old\":\"gone();\",\"new\":\"\"}]}");
    EditBlocks.Edit e = p.edits().get(0).edit();
    assertNull(e.anchor());
    assertFalse(e.hasAnchor());
    assertEquals("", e.replace());
    assertFalse(new EditBlocks.Edit("a", "b", "  ").hasAnchor(), "a blank anchor is no anchor");
  }

  @Test
  void anEmptyOldIsReadHereAndRefusedWhereItIsApplied() throws Exception {
    JsonEdits.Parsed p = JsonEdits.parse(reply("", edit("New.java", "", "", "class New {}")));
    assertEquals("", p.edits().get(0).edit().search());
    AiException ex =
        assertThrows(
            AiException.class,
            () -> EditBlocks.apply("class Old {}\n", List.of(p.edits().get(0).edit())));
    assertEquals(FailureReason.UNPARSEABLE, ex.reason());
  }

  @Test
  void editsAreGroupedByFileInTheOrderTheyAppear() throws Exception {
    JsonEdits.Parsed p =
        JsonEdits.parse(
            reply(
                "",
                edit("B.java", "", "b1", "B1"),
                edit("A.java", "", "a1", "A1"),
                edit("B.java", "", "b2", "B2")));
    assertEquals(List.of("B.java", "A.java"), JsonEdits.files(p));
    assertEquals(
        List.of("b1", "b2"),
        JsonEdits.editsFor(p, "B.java").stream().map(EditBlocks.Edit::search).toList());
    assertEquals(List.of(), JsonEdits.editsFor(p, "C.java"));
  }

  @Test
  void aSectionHoldsOneFilesEditsAndCanBeReadAgain() throws Exception {
    JsonEdits.Parsed p =
        JsonEdits.parse(
            reply(
                "all",
                edit("B.java", "void f() {", "b1", "B1"),
                edit("A.java", "", "a1", "A1"),
                edit("B.java", "", "b2", "B2")));
    String section = JsonEdits.section(p, "B.java");
    assertTrue(JsonEdits.looksLike(section));
    JsonEdits.Parsed again = JsonEdits.parse(section);
    assertEquals(List.of("B.java"), JsonEdits.files(again));
    assertEquals(JsonEdits.editsFor(p, "B.java"), JsonEdits.editsFor(again, "B.java"));
  }

  @Test
  void aFolderInFrontOfTheNameIsNotADifferentFile() {
    assertTrue(JsonEdits.sameFile("Foo.java", "Foo.java"));
    assertTrue(JsonEdits.sameFile("src/main/Foo.java", "Foo.java"));
    assertTrue(JsonEdits.sameFile("Foo.java", "src/Foo.java"));
    assertTrue(JsonEdits.sameFile("src\\Foo.java", "src/Foo.java"));
    assertFalse(JsonEdits.sameFile("Bar.java", "Foo.java"));
  }

  @Test
  void theSchemaIsJsonThatNamesTheFourFields() {
    Object schema = Json.parse(JsonEdits.SCHEMA);
    Map<String, Object> root = Json.asObject(schema);
    assertEquals("object", Json.string(root, "type"));
    Map<String, Object> item =
        Json.asObject(Json.get(Json.get(Json.get(root, "properties"), "edits"), "items"));
    assertEquals(
        List.of("file", "anchor", "old", "new"),
        Json.asArray(Json.get(item, "required")).stream().map(String::valueOf).toList());
    for (String field : List.of("file", "anchor", "old", "new"))
      assertEquals("string", Json.string(Json.get(Json.get(item, "properties"), field), "type"));
  }
}
