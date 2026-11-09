package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MethodFinderTest {
  private static final String SOURCE =
      """
      package demo;

      import java.util.List;

      public class Calc {
        private final int base = 1;

        public Calc() {
          // nothing
        }

        @Override
        @SuppressWarnings("unused")
        public int add(int a, int b) {
          String s = "} not a brace {";
          return a + b; // }
        }

        public int add(int a, int b, int c) {
          return add(add(a, b), c);
        }

        abstract int later(int x);

        static class Inner {
          void helper() {
            new Thread(() -> {
              System.out.println("x");
            }).start();
          }
        }
      }
      """;

  @Test
  void aMethodIsFoundWithItsAnnotationsAndItsWholeBody() {
    List<MethodFinder.Found> found = MethodFinder.find(SOURCE, "helper");
    assertEquals(1, found.size());
    assertTrue(found.get(0).text().startsWith("    void helper() {"));
    assertTrue(found.get(0).text().endsWith("    }"));
    MethodFinder.Found add = MethodFinder.find(SOURCE, "add").get(0);
    assertTrue(add.text().startsWith("  @Override\n  @SuppressWarnings(\"unused\")\n"), add.text());
    assertTrue(add.text().contains("return a + b;"));
    assertEquals(12, add.firstLine());
    assertEquals(17, add.lastLine());
    assertTrue(add.hasBody());
  }

  @Test
  void bracesInStringsAndCommentsDoNotEndAMethod() {
    String text = MethodFinder.find(SOURCE, "add").get(0).text();
    assertTrue(text.endsWith("}"), text);
    assertFalse(text.contains("add(add"), "stops at its own closing brace, not the next method");
  }

  @Test
  void everyOverloadIsReturned() {
    assertEquals(2, MethodFinder.find(SOURCE, "add").size());
  }

  @Test
  void aConstructorIsAMethodButItsClassIsNot() {
    List<MethodFinder.Found> found = MethodFinder.find(SOURCE, "Calc");
    assertEquals(1, found.size());
    assertTrue(found.get(0).text().contains("public Calc()"));
    assertTrue(MethodFinder.find(SOURCE, "Inner").isEmpty(), "a nested class is not a method");
  }

  @Test
  void aMethodWithoutABodyCoversItsDeclarationLine() {
    MethodFinder.Found later = MethodFinder.find(SOURCE, "later").get(0);
    assertFalse(later.hasBody());
    assertEquals(later.firstLine(), later.lastLine());
    assertEquals("abstract int later(int x);", later.text().strip());
  }

  @Test
  void aMethodWhoseBracesDoNotBalanceIsTheDeclarationLineOnly() {
    MethodFinder.Found broken =
        MethodFinder.find("class A {\n  void broken() {\n    int x;\n", "broken").get(0);
    assertFalse(broken.hasBody());
    assertEquals("  void broken() {", broken.text());
  }

  @Test
  void callsAreNotMistakenForBodylessMethods() {
    String text = "class A {\n  void f() {\n    other(1);\n    Thread.sleep(2);\n  }\n}\n";
    assertTrue(MethodFinder.find(text, "other").isEmpty());
    assertTrue(MethodFinder.find(text, "sleep").isEmpty());
    assertEquals(1, MethodFinder.find(text, "f").size());
  }

  @Test
  void anInterfacesMethodsAreSignatures() {
    List<String> signatures =
        MethodFinder.signatures("interface Shape {\n  double area();\n  String name(int n);\n}\n");
    assertEquals(List.of("interface Shape {", "double area();", "String name(int n);"), signatures);
  }

  @Test
  void aNameThatIsNotThereOrNotAnIdentifierFindsNothing() {
    assertTrue(MethodFinder.find(SOURCE, "missing").isEmpty());
    assertTrue(MethodFinder.find(SOURCE, "add(int").isEmpty());
    assertTrue(MethodFinder.find(SOURCE, "").isEmpty());
    assertTrue(MethodFinder.find(SOURCE, null).isEmpty());
  }

  @Test
  void signaturesListTypesAndMembersButNotWhatIsInsideABody() {
    List<String> signatures = MethodFinder.signatures(SOURCE);
    assertTrue(signatures.contains("public class Calc {"));
    assertTrue(signatures.contains("public Calc() {"));
    assertTrue(signatures.contains("public int add(int a, int b) {"));
    assertTrue(signatures.contains("public int add(int a, int b, int c) {"));
    assertTrue(signatures.contains("abstract int later(int x);"));
    assertTrue(signatures.contains("static class Inner {"));
    assertTrue(signatures.contains("void helper() {"));
    assertTrue(
        signatures.stream().noneMatch(s -> s.contains("Thread") || s.contains("println")),
        "calls inside a body are not declarations: " + signatures);
  }

  @Test
  void declaresAlsoKnowsFunctionsOfOtherLanguages() {
    assertTrue(MethodFinder.declares("def total(items):\n    return 1\n", "total"));
    assertTrue(MethodFinder.declares("export function total(a) {\n  return a;\n}\n", "total"));
    assertTrue(MethodFinder.declares("fn total(a: i32) -> i32 {\n  a\n}\n", "total"));
    assertFalse(MethodFinder.declares("def total(items):\n    return 1\n", "other"));
    var python = MethodFinder.find("def total(items):\n    return 1\n", "total");
    assertFalse(python.get(0).hasBody(), "no braces: only the declaration line is known");
  }

  @Test
  void identifiersAreChecked() {
    assertTrue(MethodFinder.isIdentifier("add"));
    assertTrue(MethodFinder.isIdentifier("_x$1"));
    assertFalse(MethodFinder.isIdentifier("1x"));
    assertFalse(MethodFinder.isIdentifier("a b"));
    assertFalse(MethodFinder.isIdentifier(null));
  }
}
