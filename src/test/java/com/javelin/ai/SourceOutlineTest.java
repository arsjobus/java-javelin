package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.SourceOutline.Kind;
import com.javelin.ai.SourceOutline.Member;
import com.javelin.ai.SourceOutline.Outline;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceOutlineTest {
  private static final String CALC =
      """
      package demo;
      import java.util.List;

      /** Doc. */
      public class Calc {
          private final List<String> log = new java.util.ArrayList<>();
          private int total;

          public Calc() { total = 0; }

          @Override
          public int add(int a,
                         int b) {
              total += a + b;
              return a + b;
          }

          static class Inner {
              int v;
              int get() { return v; }
          }

          abstract int hook(int x);
      }
      """;

  private static Member find(Outline o, String qualified) {
    return o.members().stream()
        .filter(m -> m.qualified().equals(qualified))
        .findFirst()
        .orElseThrow(() -> new AssertionError(qualified + " not in " + o.members()));
  }

  @Test
  void typesMethodsConstructorsAndFieldsAreFoundWithTheirOwners() {
    Outline o = SourceOutline.of(CALC);
    assertTrue(o.isComplete());
    assertEquals(Kind.TYPE, find(o, "Calc").kind());
    assertEquals(Kind.FIELD, find(o, "Calc.log").kind());
    assertEquals(Kind.FIELD, find(o, "Calc.total").kind());
    assertEquals(Kind.CONSTRUCTOR, find(o, "Calc.Calc").kind());
    assertEquals(Kind.METHOD, find(o, "Calc.add").kind());
    assertEquals(Kind.TYPE, find(o, "Calc.Inner").kind());
    assertEquals(Kind.FIELD, find(o, "Calc.Inner.v").kind());
    assertEquals(Kind.METHOD, find(o, "Calc.Inner.get").kind());
    assertEquals("Calc.Inner", find(o, "Calc.Inner.get").owner());
  }

  @Test
  void aMethodCoversItsAnnotationsAndAllLinesOfItsBody() {
    Member add = find(SourceOutline.of(CALC), "Calc.add");
    assertEquals(11, add.first(), "the line of @Override");
    assertEquals(16, add.last());
    assertEquals(13, add.open(), "the brace is on the second line of the declaration");
    assertTrue(add.hasBody());
    assertEquals("public int add(int a, int b)", add.signature());
  }

  @Test
  void aMethodWithNoBodyHasASignatureAndNoBody() {
    Member hook = find(SourceOutline.of(CALC), "Calc.hook");
    assertFalse(hook.hasBody());
    assertEquals("abstract int hook(int x)", hook.signature());
    assertEquals(hook.first(), hook.last());
  }

  @Test
  void aFieldKeepsItsInitializerInTheSignature() {
    assertEquals(
        "private final List<String> log = new java.util.ArrayList<>()",
        find(SourceOutline.of(CALC), "Calc.log").signature());
  }

  @Test
  void bracesInStringsAndCommentsDoNotEndAMethod() {
    String text =
        "class A {\n"
            + "  String f() {\n"
            + "    String s = \"}\"; // }\n"
            + "    return s;\n"
            + "  }\n"
            + "  int g() { return 1; }\n"
            + "}\n";
    Outline o = SourceOutline.of(text);
    assertEquals(2, find(o, "A.f").first());
    assertEquals(5, find(o, "A.f").last());
    assertEquals(6, find(o, "A.g").first());
  }

  @Test
  void aLineAtMemberLevelThatIsNotAMemberMakesTheOutlineIncomplete() {
    Outline stray =
        SourceOutline.of("class A {\n  int f() { return 1; }\n  what is this line\n}\n");
    assertFalse(stray.isComplete());
    assertEquals(1, stray.unrecognisedLines());
    assertEquals(3, stray.firstUnrecognised());
    Outline unbalanced = SourceOutline.of("class A {\n  int f() { return 1; }\n");
    assertFalse(unbalanced.isComplete(), "a type that never closes cannot be trusted");
  }

  @Test
  void enumConstantsStaticBlocksAndMultiLineFieldsAreMembersNotStrayLines() {
    Outline o =
        SourceOutline.of(
            "enum Color {\n  RED, GREEN;\n  int f() { return 1; }\n}\n"
                + "class B {\n  static {\n    init();\n  }\n"
                + "  static final List<String> L = List.of(\n      \"a;\");\n"
                + "  @Test void t() { }\n  @A(x = 1,\n    y = 2)\n  void u() { }\n}\n");
    assertTrue(o.isComplete(), "first stray line: " + o.firstUnrecognised());
    assertEquals(Kind.FIELD, find(o, "Color.<constants>").kind());
    assertEquals(Kind.INITIALIZER, find(o, "B.<static>").kind());
    assertEquals(Kind.FIELD, find(o, "B.L").kind());
    assertEquals(
        1,
        find(o, "B.L").last() - find(o, "B.L").first(),
        "it covers two lines: the ; inside the string is not the end");
    assertEquals(Kind.METHOD, find(o, "B.t").kind(), "an annotation on the same line");
    assertEquals(
        12, find(o, "B.u").first(), "the annotation that runs over two lines belongs to u");
  }

  @Test
  void textThatIsNotJavaGivesNoMembers() {
    assertEquals(List.of(), SourceOutline.of("# Title\n\nSome words.\n").members());
    assertEquals(List.of(), SourceOutline.of("").members());
  }
}
