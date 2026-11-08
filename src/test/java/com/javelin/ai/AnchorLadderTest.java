package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ladder that places an edit: exact text, then ignoring spaces at line ends, then indentation,
 * then any run of spaces, then the signature of a whole method. The rung that matched is recorded.
 */
class AnchorLadderTest {
  private static final String CODE =
      """
      class Foo {
          int add(int a, int b) {
              return a - b;
          }

          int twice(int a) {
              return a * 2;
          }
      }
      """;

  private static EditBlocks.Applied apply(String code, String search, String replace)
      throws AiException {
    return EditBlocks.applyWithAnchors(code, List.of(new EditBlocks.Edit(search, replace)));
  }

  private static AiException refused(String code, String search, String replace) {
    return assertThrows(AiException.class, () -> apply(code, search, replace));
  }

  private static AnchorRung rung(EditBlocks.Applied applied) {
    assertEquals(1, applied.anchors().size());
    assertEquals(1, applied.anchors().get(0).edit());
    return applied.anchors().get(0).rung();
  }

  @Test
  void exactTextIsTheFirstRung() throws Exception {
    EditBlocks.Applied a = apply(CODE, "        return a - b;", "        return a + b;");
    assertEquals(AnchorRung.EXACT, rung(a));
    assertEquals(CODE.replace("a - b", "a + b"), a.text());
  }

  @Test
  void spacesAtTheEndOfLinesAreIgnoredOnTheSecondRung() throws Exception {
    EditBlocks.Applied a = apply(CODE, "        return a - b;   ", "        return a + b;");
    assertEquals(AnchorRung.TRAILING_SPACE, rung(a));
    assertEquals(CODE.replace("a - b", "a + b"), a.text());
  }

  @Test
  void lostIndentationIsTheThirdRungAndTheReplacementIsIndentedToMatch() throws Exception {
    EditBlocks.Applied a = apply(CODE, "return a - b;", "return a + b;\n// checked");
    assertEquals(AnchorRung.INDENTATION, rung(a));
    assertEquals(
        CODE.replace("        return a - b;", "        return a + b;\n        // checked"),
        a.text());
  }

  @Test
  void anyRunOfSpacesIsTheFourthRung() throws Exception {
    EditBlocks.Applied a = apply(CODE, "int  add(int a,   int b) {", "int add(long a, int b) {");
    assertEquals(AnchorRung.WHITESPACE, rung(a));
    assertTrue(a.text().contains("    int add(long a, int b) {"), a.text());
    assertFalse(a.text().contains("int add(int a, int b)"));
  }

  @Test
  void theStrictestRungThatMatchesAnywhereWins() throws Exception {
    String code = "    x = 1;\nx = 1;\n";
    // exact text matches the second line only; ignoring indentation would match both
    EditBlocks.Applied a = apply(code, "x = 1;", "x = 2;");
    assertEquals(AnchorRung.EXACT, rung(a));
    assertEquals("    x = 1;\nx = 2;\n", a.text());
  }

  @Test
  void aLooseRungThatMatchesInTwoPlacesIsRefusedNotGuessed() {
    String code = "  x = 1;\n    x = 1;\n";
    AiException ex = refused(code, "x = 1;", "x = 2;");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("2 places"), ex.getMessage());
  }

  @Test
  void linesThatAreNotInTheCodeAreRefusedAndNothingIsChanged() {
    AiException ex = refused(CODE, "        return a / b;", "        return a + b;");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("not in the code"), ex.getMessage());
  }

  @Test
  void aSecondEditThatCannotBePlacedStopsTheWholeReply() {
    List<EditBlocks.Edit> edits =
        List.of(
            new EditBlocks.Edit("        return a - b;", "        return a + b;"),
            new EditBlocks.Edit("        return nothing;", "        return 0;"));
    AiException ex = assertThrows(AiException.class, () -> EditBlocks.apply(CODE, edits));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().startsWith("Edit 2 "), ex.getMessage());
  }

  // ---- the signature rung ----

  @Test
  void aWholeMethodWhoseBodyDiffersIsPlacedBySignature() throws Exception {
    String search =
        """
        int add(int a, int b) {
            return a - b; // the model remembered this wrongly
        }\
        """;
    String replace =
        """
        int add(int a, int b) {
            return a + b;
        }\
        """;
    EditBlocks.Applied a = apply(CODE, search, replace);
    assertEquals(AnchorRung.SIGNATURE, rung(a));
    assertEquals(CODE.replace("a - b", "a + b"), a.text());
  }

  @Test
  void theSignatureRungReindentsTheWholeMethodReplacement() throws Exception {
    String search = "int add(int a, int b) {\n    return 0;\n}";
    String replace = "int add(int a, int b) {\n    return a + b;\n}";
    EditBlocks.Applied a = apply(CODE, search, replace);
    assertEquals(AnchorRung.SIGNATURE, rung(a));
    assertEquals(CODE.replace("a - b", "a + b"), a.text());
  }

  @Test
  void theSignatureRungKeepsAnAnnotationTheModelCopied() throws Exception {
    String code =
        "class Foo {\n"
            + "    @Override\n"
            + "    public String toString() {\n"
            + "        return \"a\";\n"
            + "    }\n"
            + "}\n";
    String search = "    @Override\n    public String toString() {\n        return \"?\";\n    }";
    String replace = "    @Override\n    public String toString() {\n        return \"b\";\n    }";
    EditBlocks.Applied a = apply(code, search, replace);
    assertEquals(AnchorRung.SIGNATURE, rung(a));
    assertEquals(code.replace("\"a\"", "\"b\""), a.text());
  }

  @Test
  void theSignatureRungNeverReplacesMoreThanTheModelSearchedFor() {
    // the search is only the start of the method: the lines are wrong, and replacing the whole
    // method with three lines would delete the rest of it
    String search = "int add(int a, int b) {\n    return 0;";
    AiException ex = refused(CODE, search, "int add(int a, int b) {\n    return a + b;");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
  }

  @Test
  void theSignatureRungNeedsTheReplacementToBeTheSameMethod() {
    String search = "int add(int a, int b) {\n    return 0;\n}";
    AiException renamed = refused(CODE, search, "int plus(int a, int b) {\n    return a + b;\n}");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, renamed.reason());
    AiException notAMethod = refused(CODE, search, "return a + b;");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, notAMethod.reason());
    AiException deleted = refused(CODE, search, "");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, deleted.reason());
  }

  @Test
  void theSignatureRungNeedsTheSignatureToBeDeclaredOnce() {
    String twice =
        "class Foo {\n"
            + "  int f(int a) {\n"
            + "    return 1;\n"
            + "  }\n"
            + "  int f(int a) {\n"
            + "    return 2;\n"
            + "  }\n"
            + "}\n";
    AiException ex =
        refused(twice, "int f(int a) {\n    return 3;\n}", "int f(int a) {\n    return 4;\n}");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
  }

  @Test
  void aSignatureThatIsNotInTheCodeIsRefused() {
    AiException ex =
        refused(
            CODE,
            "int subtract(int a, int b) {\n    return 0;\n}",
            "int subtract(int a, int b) {\n    return a - b;\n}");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
  }

  @Test
  void braceInAStringOrCommentDoesNotConfuseTheMethodsEnd() throws Exception {
    String code =
        "class Foo {\n"
            + "  String f() {\n"
            + "    // }\n"
            + "    return \"}\";\n"
            + "  }\n"
            + "  int g() {\n"
            + "    return 1;\n"
            + "  }\n"
            + "}\n";
    EditBlocks.Applied a =
        apply(code, "String f() {\n  return null;\n}", "String f() {\n  return \"x\";\n}");
    assertEquals(AnchorRung.SIGNATURE, rung(a));
    assertEquals(
        "class Foo {\n  String f() {\n    return \"x\";\n  }\n  int g() {\n    return 1;\n  }\n}\n",
        a.text());
  }

  // ---- what the review is told ----

  @Test
  void anEditFoundByAnythingButExactTextIsFlaggedForTheReview() throws Exception {
    ChangeReply.Interpreted loose =
        ChangeReply.interpret(
            "<<<<<<< SEARCH\nreturn a - b;\n=======\nreturn a + b;\n>>>>>>> REPLACE", CODE);
    assertEquals(ChangeReply.Format.EDIT_BLOCKS, loose.format());
    assertEquals(AnchorRung.INDENTATION, loose.anchors().get(0).rung());
    assertEquals(1, loose.notes().size());
    assertTrue(loose.notes().get(0).message().contains("ignoring indentation"));
    assertFalse(loose.notes().get(0).isError());

    ChangeReply.Interpreted exact =
        ChangeReply.interpret(
            "<<<<<<< SEARCH\n"
                + "        return a - b;\n"
                + "=======\n"
                + "        return a + b;\n"
                + ">>>>>>> REPLACE",
            CODE);
    assertEquals(AnchorRung.EXACT, exact.anchors().get(0).rung());
    assertTrue(exact.notes().isEmpty());
  }

  @Test
  void aMethodPlacedBySignatureIsFlaggedAsAWholeMethodReplacement() throws Exception {
    ChangeReply.Interpreted signature =
        ChangeReply.interpret(
            "<<<<<<< SEARCH\n"
                + "int add(int a, int b) {\n"
                + "  return 9;\n"
                + "}\n"
                + "=======\n"
                + "int add(int a, int b) {\n"
                + "  return a + b;\n"
                + "}\n"
                + ">>>>>>> REPLACE",
            CODE);
    assertEquals(AnchorRung.SIGNATURE, signature.anchors().get(0).rung());
    assertTrue(signature.notes().get(0).message().contains("whole method was replaced"));
  }

  @Test
  void theRungsAreKeptOnTheProposal() throws Exception {
    AiContext context =
        AiContext.of(
            AiAction.PROPOSE_CHANGE,
            new AiSource("Foo.java", "Foo.java", "java", CODE, 0, CODE.length()),
            12_000);
    ChangeReply.Interpreted loose =
        ChangeReply.interpret(
            "<<<<<<< SEARCH\nreturn a - b;\n=======\nreturn a + b;\n>>>>>>> REPLACE", CODE);
    AiProposal proposal = AiProposal.replace(context, loose.code(), "", loose.anchors());
    assertEquals(List.of(new EditBlocks.Anchor(1, AnchorRung.INDENTATION)), proposal.anchors());
    assertEquals(List.of(), AiProposal.replace(context, loose.code(), "").anchors());
  }
}
