package com.javelin.launch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchArgumentsTest {
    @TempDir Path work;

    @Test void noArgumentsMeansNothingToOpen() {
        LaunchArguments.Result r = LaunchArguments.parse(List.of(), work);
        assertTrue(r.directory().isEmpty());
        assertTrue(r.problem().isEmpty());
    }

    @Test void anAbsoluteDirectoryIsOpened() throws IOException {
        Path project = Files.createDirectory(work.resolve("proj"));
        LaunchArguments.Result r = LaunchArguments.parse(List.of(project.toString()), Path.of("/"));
        assertEquals(project.toAbsolutePath().normalize(), r.directory().orElseThrow());
        assertTrue(r.problem().isEmpty());
    }

    @Test void aRelativeDirectoryIsTakenFromTheWorkingDirectory() throws IOException {
        Files.createDirectories(work.resolve("a/b"));
        assertEquals(work.resolve("a/b").toAbsolutePath().normalize(), LaunchArguments.parse(List.of("a/b"), work).directory().orElseThrow());
        assertEquals(work.toAbsolutePath().normalize(), LaunchArguments.parse(List.of("a/.."), work).directory().orElseThrow());
        assertEquals(work.toAbsolutePath().normalize(), LaunchArguments.parse(List.of("."), work).directory().orElseThrow());
    }

    @Test void aFileOrAMissingPathIsReportedNotOpened() throws IOException {
        Files.writeString(work.resolve("f.txt"), "x");
        LaunchArguments.Result file = LaunchArguments.parse(List.of("f.txt"), work);
        assertTrue(file.directory().isEmpty());
        assertEquals("Not a directory: f.txt", file.problem().orElseThrow());
        assertEquals("Not a directory: nope", LaunchArguments.parse(List.of("nope"), work).problem().orElseThrow());
    }

    @Test void optionsBlanksAndNullsAreSkippedAndTheFirstPlainArgumentWins() throws IOException {
        Path first = Files.createDirectory(work.resolve("first"));
        Files.createDirectory(work.resolve("second"));
        LaunchArguments.Result r = LaunchArguments.parse(Arrays.asList("-psn_0_12345", "--flag", "", "  ", null, "first", "second"), work);
        assertEquals(first.toAbsolutePath().normalize(), r.directory().orElseThrow());
    }

    @Test void onlyOptionsMeansNothingToOpen() {
        LaunchArguments.Result r = LaunchArguments.parse(List.of("-psn_0_1", "-x"), work);
        assertTrue(r.directory().isEmpty());
        assertTrue(r.problem().isEmpty());
    }

    @Test void anInvalidPathIsReported() {
        LaunchArguments.Result r = LaunchArguments.parse(List.of("bad\0name"), work);
        assertTrue(r.directory().isEmpty());
        assertEquals("Not a valid path: bad\0name", r.problem().orElseThrow());
    }
}
