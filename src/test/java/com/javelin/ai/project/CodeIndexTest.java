package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodeIndexTest {
  private static CodeFacts facts(String path, String language, String text) {
    return new CodeFacts(
        new ProjectFile(path, text.length(), 0, language, ProjectFile.Kind.SOURCE), text);
  }

  private final CodeIndex index =
      new CodeIndex(
          List.of(
              facts("web/lib/util.js", "javascript", ""),
              facts("web/lib/index.ts", "typescript", ""),
              facts("web/pages/home.jsx", "javascriptreact", ""),
              facts("pkg/mod.py", "python", ""),
              facts("src/pkg2/__init__.py", "python", ""),
              facts("A.java", "java", "class A {}")));

  @Test
  void relativeImportsTryTheUsualExtensionsAndIndexFiles() {
    assertEquals("web/lib/util.js", index.relative("web/pages", "../lib/util"));
    assertEquals("web/lib/index.ts", index.relative("web/pages", "../lib"));
    assertEquals("web/pages/home.jsx", index.relative("web/pages", "./home"));
    assertNull(index.relative("web/pages", "./missing"));
  }

  @Test
  void aRelativeImportThatLeavesTheProjectIsIgnored() {
    assertNull(index.relative("web", "../../../outside"));
    assertNull(index.relative("", "../x"));
  }

  @Test
  void pythonModulesResolveAtTheTopOrUnderSrc() {
    assertEquals("pkg/mod.py", index.pythonModule("pkg.mod"));
    assertEquals("src/pkg2/__init__.py", index.pythonModule("pkg2"));
    assertNull(index.pythonModule("os.path"));
  }

  @Test
  void javaTypesInTheDefaultPackageAreFound() {
    assertEquals("A.java", index.byQualifiedName("A"));
    assertEquals("A.java", index.bySimpleNameInPackage("", "A"));
    assertEquals(List.of("A.java"), index.inPackage(""));
    assertEquals(List.of(), index.inPackage("nope"));
  }
}
