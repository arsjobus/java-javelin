package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.project.ProjectFile.Kind;
import org.junit.jupiter.api.Test;

class FileKindsTest {
  @Test
  void languagesComeFromTheExtension() {
    assertEquals("java", FileKinds.languageOf("src/A.java"));
    assertEquals("markdown", FileKinds.languageOf("README.md"));
    assertEquals("yaml", FileKinds.languageOf("ci/build.YML"));
    assertEquals("xml", FileKinds.languageOf("pom.xml"));
    assertEquals("makefile", FileKinds.languageOf("Makefile"));
    assertNull(FileKinds.languageOf("LICENSE"));
    assertNull(FileKinds.languageOf("data.unknownext"));
  }

  @Test
  void kindsSeparateDocsBuildSourceTestsAndConfiguration() {
    assertEquals(Kind.BUILD, FileKinds.kindOf("pom.xml", "xml"));
    assertEquals(Kind.BUILD, FileKinds.kindOf("app/build.gradle.kts", "kotlin"));
    assertEquals(Kind.DOC, FileKinds.kindOf("docs/guide.md", "markdown"));
    assertEquals(Kind.DOC, FileKinds.kindOf("README", null));
    assertEquals(Kind.DOC, FileKinds.kindOf("LICENSE", null));
    assertEquals(Kind.SOURCE, FileKinds.kindOf("src/A.java", "java"));
    assertEquals(Kind.TEST, FileKinds.kindOf("src/test/java/ATest.java", "java"));
    assertEquals(Kind.CONFIG, FileKinds.kindOf("conf/app.yaml", "yaml"));
    assertEquals(Kind.CONFIG, FileKinds.kindOf(".editorconfig", null));
    assertEquals(Kind.CONFIG, FileKinds.kindOf("Dockerfile", null));
    assertEquals(Kind.OTHER, FileKinds.kindOf("notes.weird", null));
  }

  @Test
  void testsAreRecognisedByFolderAndByTheNamesOfEachLanguage() {
    assertTrue(FileKinds.isTest("tests/a.py"));
    assertTrue(FileKinds.isTest("pkg/tests/a.py"));
    assertTrue(FileKinds.isTest("src/__tests__/a.js"));
    assertTrue(FileKinds.isTest("FooTest.java"));
    assertTrue(FileKinds.isTest("FooTests.kt"));
    assertTrue(FileKinds.isTest("FooIT.java"));
    assertTrue(FileKinds.isTest("test_foo.py"));
    assertTrue(FileKinds.isTest("foo_test.go"));
    assertTrue(FileKinds.isTest("foo.test.ts"));
    assertTrue(FileKinds.isTest("foo.spec.js"));
    assertFalse(FileKinds.isTest("src/Latest.java"));
    assertFalse(FileKinds.isTest("src/contest/A.java".replace("contest", "main")));
  }
}
