package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class LanguagesTest {
    @ParameterizedTest
    @CsvSource({"A.java,java", "a.PY,python", "a.pyi,python", "a.js,javascript", "a.mjs,javascript", "a.jsx,javascriptreact", "a.ts,typescript", "a.tsx,typescriptreact",
        "a.go,go", "a.rs,rust", "a.c,c", "a.h,c", "a.cpp,cpp", "a.hh,cpp", "a.kt,kotlin", "a.kts,kotlin", "a.rb,ruby", "a.php,php", "a.json,json", "a.sh,shellscript", "a.bash,shellscript",
        "archive.tar.java,java"})
    void knownExtensions(String file, String id) { assertEquals(id, Languages.idFor(new File(file))); }

    @ParameterizedTest
    @CsvSource({"Makefile", ".bashrc", "trailing.", "a.unknown", "README"})
    void unknownExtensions(String file) { assertNull(Languages.idFor(new File(file))); }

    @Test void nullFile() { assertNull(Languages.idFor(null)); }

    @Test void everyRecognizedLanguageHasASuggestedServer() {
        for (String id : new String[] {"java", "python", "javascript", "javascriptreact", "typescript", "typescriptreact", "go", "rust", "c", "cpp", "kotlin", "ruby", "php", "json", "shellscript"})
            assertFalse(Languages.suggestedCommand(id).isBlank(), id);
        assertEquals("", Languages.suggestedCommand("cobol"));
    }
}
