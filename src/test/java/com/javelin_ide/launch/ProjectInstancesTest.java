package com.javelin_ide.launch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectInstancesTest {
    @TempDir Path home;

    private ProjectInstances instances() { return new ProjectInstances(home.resolve("config").resolve(ProjectInstances.DIRECTORY_NAME)); }

    private Path project(String name) throws IOException { return Files.createDirectories(home.resolve(name)); }

    private Path onlyLockFile() throws IOException {
        try (Stream<Path> files = Files.list(home.resolve("config").resolve(ProjectInstances.DIRECTORY_NAME))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".lock")).findFirst().orElseThrow();
        }
    }

    private static void age(Path file, int days) throws IOException { Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(days)))); }

    @Test void aFreeProjectCanBeClaimedOnceAndNotTwice() throws IOException {
        Path demo = project("demo");
        try (ProjectInstances.Claim first = instances().claim(demo).orElseThrow()) {
            assertEquals(demo.toRealPath(), first.project());
            assertTrue(instances().claim(demo).isEmpty(), "already open, even asked through another object");
        }
    }

    @Test void theFolderForTheLocksIsCreatedOnDemand() throws IOException {
        Path folder = home.resolve("config").resolve(ProjectInstances.DIRECTORY_NAME);
        assertFalse(Files.exists(folder));
        instances().claim(project("demo")).orElseThrow().close();
        assertTrue(Files.isDirectory(folder));
    }

    @Test void differentProjectsDoNotGetInEachOthersWay() throws IOException {
        try (ProjectInstances.Claim a = instances().claim(project("a")).orElseThrow(); ProjectInstances.Claim b = instances().claim(project("b")).orElseThrow()) {
            assertNotEquals(a.project(), b.project());
        }
    }

    @Test void theSameFolderSpelledDifferentlyIsTheSameProject() throws IOException {
        Path demo = project("demo");
        try (ProjectInstances.Claim first = instances().claim(demo).orElseThrow()) {
            assertTrue(instances().claim(home.resolve("other").resolve("..").resolve("demo")).isEmpty(), "a path with .. in it");
            Path link = Files.createSymbolicLink(home.resolve("link"), demo);
            assertTrue(instances().claim(link).isEmpty(), "a symbolic link to it");
            assertEquals(first.project(), demo.toRealPath());
        }
    }

    @Test void closingGivesTheProjectBackAndClosingTwiceIsHarmless() throws IOException {
        Path demo = project("demo");
        ProjectInstances.Claim first = instances().claim(demo).orElseThrow();
        first.close();
        ProjectInstances.Claim second = instances().claim(demo).orElseThrow();
        first.close(); // a stale handle must not release the new window's claim
        assertTrue(instances().claim(demo).isEmpty(), "the second claim still holds");
        second.close();
        instances().claim(demo).orElseThrow().close();
    }

    @Test void aFolderThatDoesNotExistYetStillHasAnIdentity() throws IOException {
        Path future = home.resolve("not-created-yet");
        try (ProjectInstances.Claim claim = instances().claim(future).orElseThrow()) {
            assertEquals(future.toAbsolutePath().normalize(), claim.project());
            assertTrue(instances().claim(future).isEmpty());
        }
    }

    @Test void theLockFileSaysWhichProjectItIsFor() throws IOException {
        Path demo = project("demo");
        try (ProjectInstances.Claim claim = instances().claim(demo).orElseThrow()) {
            assertEquals(demo.toRealPath() + "\n", Files.readString(onlyLockFile()));
        }
    }

    @Test void aFailedClaimLeavesNothingBehind() throws IOException {
        Path demo = project("demo");
        Files.writeString(home.resolve("config"), "a file where the folder should go");
        assertThrows(IOException.class, () -> instances().claim(demo));
        Files.delete(home.resolve("config"));
        instances().claim(demo).orElseThrow().close(); // the failure did not leave the project marked as held
    }

    @Test void oldUnusedLockFilesArePrunedAndRecentOnesAreKept() throws IOException {
        instances().claim(project("old")).orElseThrow().close();
        Path oldLock = onlyLockFile();
        age(oldLock, 40);
        instances().claim(project("recent")).orElseThrow().close();

        assertEquals(1, instances().prune(Duration.ofDays(30)));
        assertFalse(Files.exists(oldLock));
        assertTrue(Files.exists(onlyLockFile()), "the recently used one stays");
    }

    @Test void aLockThisProcessHoldsIsNeverPrunedHoweverOld() throws IOException {
        try (ProjectInstances.Claim claim = instances().claim(project("demo")).orElseThrow()) {
            age(onlyLockFile(), 400);
            assertEquals(0, instances().prune(Duration.ofDays(30)));
            assertTrue(instances().claim(project("demo")).isEmpty(), "still held after the prune");
        }
    }

    @Test void pruningIgnoresOtherFilesAndMissingFolders() throws IOException {
        assertEquals(0, instances().prune(Duration.ZERO), "no folder yet");
        instances().claim(project("demo")).orElseThrow().close();
        Path other = Files.writeString(onlyLockFile().resolveSibling("notes.txt"), "mine");
        age(other, 400);
        assertEquals(0, instances().prune(Duration.ofDays(30)), "the lock file is new, and notes.txt is not a lock file");
        assertTrue(Files.exists(other), "only .lock files are ever touched");
    }

    @Test void aLockFileThatCannotBeExaminedIsLeftAlone() throws IOException {
        Path folder = Files.createDirectories(home.resolve("config").resolve(ProjectInstances.DIRECTORY_NAME));
        Path odd = Files.createDirectory(folder.resolve("odd.lock"));
        age(odd, 400);
        assertEquals(0, instances().prune(Duration.ofDays(30)));
        assertTrue(Files.isDirectory(odd));
    }
}
