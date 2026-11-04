package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestFileNamesTest {
    @Test void eachLanguageUsesItsOwnConvention() {
        assertEquals("FooTest.java", TestFileNames.suggest("Foo.java", "java"));
        assertEquals("FooTest.kt", TestFileNames.suggest("Foo.kt", "kotlin"));
        assertEquals("test_util.py", TestFileNames.suggest("util.py", "python"));
        assertEquals("util.test.js", TestFileNames.suggest("util.js", "javascript"));
        assertEquals("util.test.tsx", TestFileNames.suggest("util.tsx", "typescriptreact"));
        assertEquals("util_test.go", TestFileNames.suggest("util.go", "go"));
        assertEquals("my_lib_test.rs", TestFileNames.suggest("my-lib.rs", "rust"));
        assertEquals("FooTest.php", TestFileNames.suggest("Foo.php", "php"));
    }

    @Test void pathsAreReducedToTheFileName() {
        assertEquals("FooTest.java", TestFileNames.suggest("src/main/java/Foo.java", "java"));
        assertEquals("FooTest.java", TestFileNames.suggest("C:\\p\\Foo.java", "java"));
    }

    @Test void aSourceThatAlreadyEndsInTestIsNotDoubled() {
        assertEquals("FooTest.java", TestFileNames.suggest("FooTest.java", "java"));
    }

    @Test void unsavedAndUnknownFilesStillGetAName() {
        assertEquals("UntitledTest.java", TestFileNames.suggest(null, "java"));
        assertEquals("notes.test.txt", TestFileNames.suggest("notes.txt", null));
        assertEquals("Makefile.test.txt", TestFileNames.suggest("Makefile", null));
        assertEquals("Untitled.test.txt", TestFileNames.suggest(null, null));
        assertEquals("test_script.py", TestFileNames.suggest("script", "python"));
    }
}
