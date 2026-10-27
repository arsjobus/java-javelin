package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFinderTest {
    @TempDir Path root;

    private Path file(String relative) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "x");
    }

    @Test void findsAFileInASubFolder() throws IOException {
        Path expected = file("src/main/Main.java");
        assertEquals(expected.toFile(), ProjectFinder.find(root.toFile(), "Main.java", Set.of()).orElseThrow());
    }

    @Test void aFileInTheCurrentFolderBeatsOneDeeper() throws IOException {
        file("deep/er/Same.txt");
        Path top = file("Same.txt");
        assertEquals(top.toFile(), ProjectFinder.find(root.toFile(), "Same.txt", Set.of()).orElseThrow());
    }

    @Test void excludedFoldersAreNeverEntered() throws IOException {
        file("target/Built.class");
        file(".git/config");
        assertTrue(ProjectFinder.find(root.toFile(), "Built.class", Set.of("target", ".git")).isEmpty());
        assertTrue(ProjectFinder.find(root.toFile(), "config", Set.of("target", ".git")).isEmpty());
        assertTrue(ProjectFinder.find(root.toFile(), "Built.class", Set.of()).isPresent());
    }

    @Test void nameMustMatchExactlyAndFoldersDoNotCount() throws IOException {
        file("a/Readme.md");
        Files.createDirectories(root.resolve("Readme.md.d/Notes"));
        assertTrue(ProjectFinder.find(root.toFile(), "readme.md", Set.of()).isEmpty());
        assertTrue(ProjectFinder.find(root.toFile(), "Notes", Set.of()).isEmpty());
    }

    @Test void aMissingOrUnreadableFolderGivesNothing() {
        assertTrue(ProjectFinder.find(new File(root.toFile(), "nope"), "x", Set.of()).isEmpty());
    }
}
