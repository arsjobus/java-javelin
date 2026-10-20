package com.javelin_ide.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalCompletionTest {
    @TempDir Path dir;

    @Test void completesAUniqueFileAndAddsASpace() throws Exception {
        Files.writeString(dir.resolve("README.md"), "test");
        assertEquals("cat README.md ", TerminalCompletion.complete("cat READ", 8, dir, Map.of(), false));
    }

    @Test void completesDirectoriesWithASeparator() throws Exception {
        Files.createDirectory(dir.resolve("source"));
        assertEquals("cd source/", TerminalCompletion.complete("cd sour", 7, dir, Map.of(), false));
    }

    @Test void extendsMultipleMatchesToTheirCommonPrefix() throws Exception {
        Files.writeString(dir.resolve("project-alpha"), "");
        Files.writeString(dir.resolve("project-beta"), "");
        assertEquals("cat project-", TerminalCompletion.complete("cat pro", 7, dir, Map.of(), false));
    }

    @Test void leavesInputAloneWhenThereIsNoMatch() {
        assertEquals("cat does-not-exist", TerminalCompletion.complete("cat does-not-exist", 18, dir, Map.of(), false));
    }

    @Test void preservesTextAfterTheCaret() throws Exception {
        Files.writeString(dir.resolve("README.md"), "");
        assertEquals("cat README.md  && echo done",
                TerminalCompletion.complete("cat READ && echo done", 8, dir, Map.of(), false));
    }

    @Test void completesAnExecutableFromPathAtCommandPosition() throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path tool = Files.createFile(bin.resolve("javac"));
        tool.toFile().setExecutable(true);
        assertEquals("javac ", TerminalCompletion.complete("jav", 3, dir, Map.of("PATH", bin.toString()), false));
    }
}
