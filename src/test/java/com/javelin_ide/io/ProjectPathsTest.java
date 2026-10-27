package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPathsTest {
    @TempDir Path root;

    @Test void filesInsideTheProjectAreShownRelative() {
        File project = root.toFile();
        File inside = root.resolve("src").resolve("Main.java").toFile();
        assertEquals(Path.of("src", "Main.java").toString(), ProjectPaths.display(project, inside));
    }

    @Test void filesOutsideTheProjectAreShownInFull() {
        File outside = new File(root.getParent().toFile(), "elsewhere.txt");
        assertEquals(outside.getPath(), ProjectPaths.display(root.toFile(), outside));
    }

    @Test void withoutAProjectTheFullPathIsShown() {
        File file = root.resolve("a.txt").toFile();
        assertEquals(file.getPath(), ProjectPaths.display(null, file));
    }

    @Test void fileUrisBecomeFiles() {
        File file = root.resolve("a b.txt").toFile();
        assertEquals(file, ProjectPaths.fromUri(file.toURI()));
        assertEquals(file, ProjectPaths.fromUri(URI.create(file.toURI().toString().replaceFirst("^file", "FILE"))));
    }

    @Test void otherUrisAndMalformedFileUrisGiveNull() {
        assertNull(ProjectPaths.fromUri(URI.create("https://example.com/a.txt")));
        assertNull(ProjectPaths.fromUri(URI.create("jar:file:/x.jar!/A.class")));
        assertNull(ProjectPaths.fromUri(URI.create("file://host/share/a.txt")), "a file URI with an authority is not a local file");
        assertNull(ProjectPaths.fromUri(URI.create("file:relative.txt")), "a non-hierarchical file URI is not usable");
    }
}
