package com.javelin.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.editor.SyntaxHighlighter.Language;
import java.io.File;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {
    @Test void theExtensionDecidesAndCaseDoesNotMatter() {
        assertEquals(Language.JAVA, LanguageDetector.of(new File("A.java")));
        assertEquals(Language.PYTHON, LanguageDetector.of(new File("tool.PY")));
        assertNull(LanguageDetector.of(new File("notes.txt")));
        assertNull(LanguageDetector.of(new File("Makefile")));
    }

    @Test void anUntitledBufferIsJava() {
        assertEquals(Language.JAVA, LanguageDetector.of((File) null));
        assertEquals(Language.JAVA, LanguageDetector.of(null, "anything"));
    }

    @Test void shellStartUpFilesAreRecognisedByName() {
        assertEquals(Language.SHELL, LanguageDetector.of(new File(".zshrc")));
        assertEquals(Language.SHELL, LanguageDetector.of(new File(".BASHRC")));
    }

    @Test void theFirstLineNamesTheLanguageOfAFileWithoutAnExtension() {
        assertEquals(Language.SHELL, LanguageDetector.of(new File("deploy"), "#!/bin/bash\necho hi\n"));
        assertEquals(Language.SHELL, LanguageDetector.of(new File("deploy"), "#!/usr/bin/env -S FOO=1 zsh\n"));
        assertEquals(Language.PYTHON, LanguageDetector.of(new File("run"), "#!/usr/bin/python3.12\n"));
        assertEquals(Language.JAVASCRIPT, LanguageDetector.of(new File("cli"), "#!/usr/bin/env node\n"));
        assertNull(LanguageDetector.of(new File("run"), "#!/usr/bin/perl\n"));
        assertNull(LanguageDetector.of(new File("run"), "echo no shebang\n"));
    }

    @Test void aFileWithAnExtensionIsNeverSecondGuessed() {
        assertNull(LanguageDetector.of(new File("notes.txt"), "#!/bin/bash\n"));
        assertEquals(Language.JAVA, LanguageDetector.of(new File("A.java"), "#!/bin/bash\n"));
    }
}
