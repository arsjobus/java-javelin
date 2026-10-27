package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWatcherTest {
    @TempDir Path dir;
    Path project;
    AtomicInteger changes = new AtomicInteger();
    AtomicReference<Set<String>> excludes = new AtomicReference<>(Set.of(".git", "target"));
    ProjectWatcher watcher;

    @BeforeEach void setUp() throws IOException {
        project = Files.createDirectory(dir.resolve("project"));
        Files.writeString(project.resolve("Main.java"), "class Main {}");
        Files.createDirectory(project.resolve("src"));
        Files.writeString(project.resolve("src").resolve("A.java"), "class A {}");
        watcher = new ProjectWatcher(excludes::get, changes::incrementAndGet);
        watcher.watch(project);
    }

    @AfterEach void tearDown() { watcher.close(); }

    // ---- what counts as a change ----

    @Test void nothingChangedMeansNothingHappens() {
        assertFalse(watcher.poll());
        assertEquals(0, changes.get());
    }

    @Test void aNewFileIsNoticedOnceAndThenBecomesTheBaseline() throws IOException {
        Files.writeString(project.resolve("New.java"), "");
        assertTrue(watcher.poll());
        assertEquals(1, changes.get());
        assertFalse(watcher.poll(), "the same change must not be reported twice");
        assertEquals(1, changes.get());
    }

    @Test void aNewFolderIsNoticed() throws IOException {
        Files.createDirectory(project.resolve("docs"));
        assertTrue(watcher.poll());
    }

    @Test void changesDeepInsideAreNoticed() throws IOException {
        Files.writeString(project.resolve("src").resolve("B.java"), "");
        assertTrue(watcher.poll());
    }

    @Test void aDeletedFileIsNoticed() throws IOException {
        Files.delete(project.resolve("Main.java"));
        assertTrue(watcher.poll());
    }

    @Test void aRenamedFileIsNoticed() throws IOException {
        Files.move(project.resolve("Main.java"), project.resolve("Renamed.java"));
        assertTrue(watcher.poll());
    }

    @Test void aMovedFileIsNoticed() throws IOException {
        Files.move(project.resolve("Main.java"), project.resolve("src").resolve("Main.java"));
        assertTrue(watcher.poll());
    }

    @Test void aFileTurningIntoAFolderOfTheSameNameIsNoticed() throws IOException {
        Files.delete(project.resolve("Main.java"));
        Files.createDirectory(project.resolve("Main.java"));
        assertTrue(watcher.poll());
    }

    @Test void editingTheContentsOfAFileIsNoticed() throws IOException {
        Files.writeString(project.resolve("Main.java"), "class Main { int x; }");
        assertTrue(watcher.poll());
    }

    @Test void aDeletedProjectFolderIsNoticed() throws IOException {
        Files.delete(project.resolve("src").resolve("A.java"));
        Files.delete(project.resolve("src"));
        Files.delete(project.resolve("Main.java"));
        assertTrue(watcher.poll());
        Files.delete(project);
        assertFalse(watcher.poll(), "an empty folder and a missing one look the same: nothing to show");
    }

    // ---- excluded names ----

    @Test void excludedFoldersAndTheirContentsAreIgnored() throws IOException {
        Path target = Files.createDirectory(project.resolve("target"));
        Files.writeString(target.resolve("out.class"), "");
        Files.writeString(Files.createDirectory(project.resolve(".git")).resolve("HEAD"), "");
        assertFalse(watcher.poll());
    }

    @Test void excludedFilesAreIgnored() throws IOException {
        Files.writeString(project.resolve("target"), "a file called like an excluded folder");
        assertFalse(watcher.poll());
    }

    @Test void changingTheExcludeListAppliesToTheNextCheck() throws IOException {
        Files.writeString(Files.createDirectory(project.resolve("build")).resolve("x.o"), "");
        assertTrue(watcher.poll(), "build/ is not excluded yet");
        excludes.set(Set.of("build"));
        assertTrue(watcher.poll(), "excluding it makes it disappear from the tree");
        assertFalse(watcher.poll());
    }

    // ---- baseline handling ----

    @Test void acknowledgeAdoptsWhatIsOnDiskWithoutReportingIt() throws IOException {
        Files.writeString(project.resolve("MadeByTheEditor.java"), "");
        watcher.acknowledge();
        assertFalse(watcher.poll());
        assertEquals(0, changes.get());
    }

    @Test void watchingAnotherFolderStartsFromItsOwnBaseline() throws IOException {
        Path other = Files.createDirectory(dir.resolve("other"));
        Files.writeString(other.resolve("x.txt"), "");
        watcher.watch(other);
        assertFalse(watcher.poll());
        Files.writeString(project.resolve("ignored.txt"), "");
        assertFalse(watcher.poll(), "the old project is no longer watched");
        Files.writeString(other.resolve("y.txt"), "");
        assertTrue(watcher.poll());
    }

    @Test void watchingNothingNeverReportsAnything() throws IOException {
        watcher.watch(null);
        assertFalse(watcher.poll());
        watcher.acknowledge();
        assertFalse(watcher.poll());
        assertEquals(0, changes.get());
    }

    @Test void aPlainFileIsNotAProject() throws IOException {
        Path file = Files.writeString(dir.resolve("plain.txt"), "x");
        watcher.watch(file);
        assertFalse(watcher.poll());
        assertEquals(Set.of(), ProjectWatcher.snapshot(file, Set.of()).keySet());
    }

    // ---- snapshot ----

    @Test void snapshotListsRelativePathsWithFoldersMarked() {
        assertEquals(Set.of("Main.java", "src/", "src" + project.getFileSystem().getSeparator() + "A.java"), ProjectWatcher.snapshot(project, Set.of()).keySet());
    }

    @Test void snapshotDoesNotFollowSymbolicLinksOutOfTheProject() throws IOException {
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "");
        try { Files.createSymbolicLink(project.resolve("link"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException ex) { assumeTrue(false, "symbolic links are not available here"); }
        watcher.acknowledge();
        Files.writeString(outside.resolve("more.txt"), "");
        assertFalse(watcher.poll(), "what is behind a link is not part of the project tree");
        assertTrue(ProjectWatcher.snapshot(project, Set.of()).containsKey("link"));
    }

    @Test void snapshotSurvivesAnUnreadableFolder() throws IOException {
        Path locked = Files.createDirectory(project.resolve("locked"));
        Files.writeString(locked.resolve("hidden.txt"), "");
        assumeTrue(locked.toFile().setReadable(false) && !Files.isReadable(locked), "permissions cannot be taken away here (root?)");
        try {
            Set<String> seen = ProjectWatcher.snapshot(project, Set.of()).keySet();
            assertTrue(seen.contains("Main.java"));
            assertTrue(seen.contains("locked/"));
        } finally {
            locked.toFile().setReadable(true);
        }
    }

    // ---- the timer ----

    @Test void theTimerReportsAnOutsideChangeWithoutAnyoneAsking() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        try (ProjectWatcher timed = new ProjectWatcher(excludes::get, seen::countDown)) {
            timed.watch(project);
            timed.start(20);
            Files.writeString(project.resolve("FromTheTerminal.java"), "");
            assertTrue(seen.await(5, TimeUnit.SECONDS), "the tree would have stayed stale");
        }
    }

    @Test void startingAgainRestartsTheTimerAndCloseIsRepeatable() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        ProjectWatcher timed = new ProjectWatcher(excludes::get, seen::countDown);
        timed.close(); // before start: harmless
        timed.watch(project);
        timed.start(10_000);
        timed.start(20);
        Files.writeString(project.resolve("late.txt"), "");
        assertTrue(seen.await(5, TimeUnit.SECONDS));
        timed.close();
        timed.close();
    }

    @Test void aCallbackThatThrowsDoesNotStopTheTimer() throws Exception {
        CountDownLatch twice = new CountDownLatch(2);
        try (ProjectWatcher timed = new ProjectWatcher(excludes::get, () -> { twice.countDown(); throw new IllegalStateException("boom"); })) {
            timed.watch(project);
            timed.start(20);
            Files.writeString(project.resolve("one.txt"), "");
            Thread.sleep(100);
            Files.writeString(project.resolve("two.txt"), "");
            assertTrue(twice.await(5, TimeUnit.SECONDS), "the second change must still be noticed");
        }
    }

    @Test void afterCloseNothingIsReported() throws Exception {
        watcher.start(20);
        watcher.close();
        Files.writeString(project.resolve("after.txt"), "");
        Thread.sleep(150);
        assertEquals(0, changes.get());
    }
}
