package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodeSpansTest {
  private static Optional<CodeSpans.Span> span(String code, int line) {
    return CodeSpans.declarationSpan(LineMatch.split(code), line);
  }

  @Test
  void declarationsAreRecognisedByTheirName() {
    assertEquals(Optional.of("add"), CodeSpans.declaredName("    int add(int a, int b) {"));
    assertEquals(
        Optional.of("find"),
        CodeSpans.declaredName("  public static <T> List<T> find(Class<T> type) {"));
    assertEquals(Optional.of("Foo"), CodeSpans.declaredName("public final class Foo<T> {"));
    assertEquals(Optional.of("Point"), CodeSpans.declaredName("record Point(int x, int y) {"));
    assertEquals(Optional.of("Foo"), CodeSpans.declaredName("  public Foo(int a) {"));
    assertEquals(Optional.of("run"), CodeSpans.declaredName("  function run(a, b) {"));
    assertEquals(
        Optional.of("get"), CodeSpans.declaredName("  Map<String, List<Integer>> get() {"));
  }

  @Test
  void statementsAndCallsAreNotDeclarations() {
    for (String line :
        List.of(
            "    if (x) {",
            "    for (int i = 0; i < n; i++) {",
            "    while (running) {",
            "    return compute(a, b);",
            "    int x = compute(a);",
            "    foo(a, b);",
            "    Foo.bar(a);",
            "    } else {",
            "    new Foo(a);",
            "    synchronized (lock) {",
            "    // int add(int a) {",
            "    @Override",
            "",
            "    throw new IllegalStateException(\"x\");"))
      assertEquals(Optional.empty(), CodeSpans.declaredName(line), line);
  }

  @Test
  void annotationOnlyLinesAreRecognised() {
    assertTrue(CodeSpans.isAnnotationOnly("    @Override"));
    assertTrue(CodeSpans.isAnnotationOnly("  @Test(timeout = 5)"));
    assertFalse(CodeSpans.isAnnotationOnly("  @Override void f() {"));
    assertFalse(CodeSpans.isAnnotationOnly("  int f() {"));
  }

  @Test
  void aSignatureKeyIgnoresSpacingAndTheOpeningBrace() {
    assertEquals(
        CodeSpans.signatureKey("  public   int f(int a)  {"),
        CodeSpans.signatureKey("public int f(int a)"));
  }

  @Test
  void aSpanRunsFromTheDeclarationToItsClosingBrace() {
    String code =
        "class A {\n"
            + "  int f() {\n"
            + "    if (x) {\n"
            + "      y();\n"
            + "    }\n"
            + "    return 1;\n"
            + "  }\n"
            + "  int g() {\n"
            + "    return 2;\n"
            + "  }\n"
            + "}\n";
    assertEquals(Optional.of(new CodeSpans.Span(1, 6)), span(code, 1));
    assertEquals(Optional.of(new CodeSpans.Span(7, 9)), span(code, 7));
    assertEquals(Optional.of(new CodeSpans.Span(0, 10)), span(code, 0));
  }

  @Test
  void bracesInCommentsStringsCharsAndTextBlocksAreNotCounted() {
    String code =
        "int f() {\n"
            + "  // }\n"
            + "  /* } */\n"
            + "  /* {\n  } */\n"
            + "  char c = '}';\n"
            + "  char q = '\\'';\n"
            + "  String s = \"}\\\"{\";\n"
            + "  String t = \"\"\"\n    }\n    \"\"\";\n"
            + "  return 1;\n"
            + "}\n"
            + "int g() {\n}\n";
    Optional<CodeSpans.Span> s = span(code, 0);
    assertTrue(s.isPresent());
    assertEquals("}", LineMatch.split(code).get(s.get().last()));
    assertEquals(12, s.get().last());
  }

  @Test
  void aDeclarationWithoutABodyHasNoSpan() {
    assertEquals(Optional.empty(), span("interface A {\n  int f();\n  int g();\n}\n", 1));
  }

  @Test
  void bracesThatDoNotBalanceHaveNoSpan() {
    assertEquals(Optional.empty(), span("int f() {\n  if (x) {\n    y();\n}\n", 0));
    assertEquals(Optional.empty(), span("int f() {\n", 0));
    assertEquals(Optional.empty(), span("}\nint f() {\n}\n", 0));
  }

  @Test
  void aParameterListOverSeveralLinesIsStillOneDeclaration() {
    String code = "int f(\n    int a,\n    int b) {\n  return a;\n}\n";
    assertEquals(Optional.of(new CodeSpans.Span(0, 4)), span(code, 0));
  }

  @Test
  void aSpanContainsTheRunsInsideIt() {
    CodeSpans.Span s = new CodeSpans.Span(3, 8);
    assertTrue(s.contains(3, 6));
    assertTrue(s.contains(5, 2));
    assertFalse(s.contains(2, 2));
    assertFalse(s.contains(7, 3));
  }
}
