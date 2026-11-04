package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeCheckerTest {
  private static final String METHOD =
      "    int add(int a, int b) {\n        return a - b;\n    }\n";

  private static List<Finding> check(String id, String before, String after) {
    return ChangeChecker.check(id, before, after);
  }

  private static boolean hasError(List<Finding> findings, String part) {
    return findings.stream().anyMatch(f -> f.isError() && f.message().contains(part));
  }

  @Test
  void aSoundChangeHasNoFindings() {
    assertEquals(List.of(), check("java", METHOD, METHOD.replace("a - b", "a + b")));
  }

  @Test
  void aChangeThatChangesNothingIsSaidSo() {
    List<Finding> f = check("java", METHOD, METHOD);
    assertEquals(1, f.size());
    assertFalse(f.get(0).isError());
    assertTrue(f.get(0).message().contains("same"));
  }

  @Test
  void aLostClosingBraceIsAnError() {
    List<Finding> f = check("java", METHOD, "    int add(int a, int b) {\n        return a + b;\n");
    assertTrue(hasError(f, "Brackets no longer balance"), f.toString());
  }

  @Test
  void bracketsInStringsAndCommentsDoNotCount() {
    String after =
        "    int add(int a, int b) {\n        // closes with }\n        String s = \"{\";\n        return a + b;\n    }\n";
    assertEquals(List.of(), check("java", METHOD, after));
  }

  @Test
  void theSelectionNeedNotBeBalancedItself() {
    assertEquals(
        List.of(), check("java", "    }\n\n    void b() {\n", "    }\n\n    void c() {\n"));
    assertTrue(
        hasError(
            check("java", "    }\n\n    void b() {\n", "    }\n\n    void c() {\n    }\n"),
            "Brackets"));
  }

  @Test
  void aPlaceholderInPlaceOfCodeIsAnError() {
    String before = "    void run() {\n        a();\n        b();\n    }\n";
    for (String placeholder :
        new String[] {
          "        // ... rest of the method",
          "        // existing code remains unchanged",
          "        /* ... */",
          "        # ... other code",
          "        ..."
        }) {
      List<Finding> f =
          check("java", before, "    void run() {\n        a();\n" + placeholder + "\n    }\n");
      assertTrue(hasError(f, "placeholder"), placeholder + " -> " + f);
    }
  }

  @Test
  void anEllipsisThatWasAlreadyInTheCodeIsNotAPlaceholderAndPythonsEllipsisIsCode() {
    String before = "def f():\n    ...\n";
    assertEquals(List.of(), check("python", before, "def g():\n    ...\n"));
    assertEquals(List.of(), check("python", "def f():\n    pass\n", "def f():\n    ...\n"));
    assertEquals(
        List.of(), check("java", "void f(String... args) {\n}\n", "void f(String... xs) {\n}\n"));
  }

  @Test
  void markersAndLineNumbersFromTheRequestAreErrors() {
    assertTrue(hasError(check("java", METHOD, "===== BEGIN CODE =====\n" + METHOD), "marker"));
    assertTrue(hasError(check("java", METHOD, "<<<<<<< SEARCH\n" + METHOD), "marker"));
    String numbered = "1: int a = 1;\n2: int b = 2;\n3: int c = 3;\n4: int d = 4;\n";
    assertTrue(
        hasError(
            check("java", "int a = 1;\nint b = 2;\nint c = 3;\nint d = 4;\n", numbered),
            "line number"));
  }

  @Test
  void javaThatNoLongerParsesIsAnErrorWithTheLine() {
    List<Finding> f =
        check("java", METHOD, "    int add(int a, int b) {\n        return a + b\n    }\n");
    assertTrue(hasError(f, "Java syntax: line 2"), f.toString());
  }

  @Test
  void javaStatementsAndWholeFilesAreCheckedToo() {
    assertTrue(
        hasError(
            check(
                "java",
                "        int x = 1;\n        run(x);\n",
                "        int x = 1;\n        run(x;\n"),
            "Java syntax"));
    assertEquals(
        List.of(),
        check(
            "java",
            "        int x = 1;\n        run(x);\n",
            "        final int x = 2;\n        run(x);\n"));
    assertTrue(
        hasError(check("java", "class A {\n}\n", "class A {\n  int = ;\n}\n"), "Java syntax"));
  }

  @Test
  void codeThatDoesNotCallItselfJavaIsOnlyCheckedForBrackets() {
    assertEquals(
        List.of(),
        check(
            "java",
            "        } else {\n            go();\n",
            "        } else {\n            stop();\n"));
    assertEquals(List.of(), check(null, "x = (1\n", "x = (2\n"));
    assertEquals(List.of(), check("markdown", "a {\n", "a\n"));
  }

  @Test
  void semanticProblemsAreNotSyntaxErrors() {
    assertEquals(
        List.of(),
        check(
            "java",
            METHOD,
            "    int add(int a, int b) {\n        return unknownHelper(a, b);\n    }\n"));
  }

  @Test
  void aMethodThatVanishedIsAWarning() {
    String before =
        "    public int add(int a, int b) {\n        return a - b;\n    }\n\n    public int sub(int a, int b) {\n        return a - b;\n    }\n";
    List<Finding> f =
        check("java", before, "    public int add(int a, int b) {\n        return a + b;\n    }\n");
    assertEquals(1, f.size());
    assertFalse(f.get(0).isError());
    assertTrue(f.get(0).message().contains("sub()"));
  }

  @Test
  void aMuchShorterReplacementIsAWarning() {
    StringBuilder before = new StringBuilder();
    for (int i = 0; i < 20; i++) before.append("        step").append(i).append("();\n");
    List<Finding> f = check("java", before.toString(), "        step0();\n        step1();\n");
    assertTrue(
        f.stream().anyMatch(x -> !x.isError() && x.message().contains("20 lines became 2")),
        f.toString());
  }

  @Test
  void errorsComeBeforeWarnings() {
    String before =
        "    public int add() {\n        return 1;\n    }\n    public int sub() {\n        return 2;\n    }\n";
    List<Finding> f = check("java", before, "    public int add() {\n        // ... rest\n    }\n");
    assertTrue(f.get(0).isError());
    assertFalse(f.get(f.size() - 1).isError());
  }

  @Test
  void windowsLineEndingsAreTreatedLikeAnyOther() {
    assertEquals(
        List.of(), check("java", METHOD.replace("\n", "\r\n"), METHOD.replace("a - b", "a + b")));
  }
}
