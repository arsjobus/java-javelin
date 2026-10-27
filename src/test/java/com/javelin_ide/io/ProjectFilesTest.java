package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin_ide.io.ProjectFiles.Contents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesTest {
    @TempDir Path dir;
    Path project;

    @BeforeEach void setUp() throws IOException {
        project = Files.createDirectory(dir.resolve("project"));
    }

    private static void link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException ex) { assumeTrue(false, "symbolic links are not available here"); }
    }

    // ---- names ----

    @Test void acceptsOrdinaryNames() {
        for (String ok : new String[] {"Main.java", "my file.txt", ".gitignore", "a", "naïve-ü.txt", "x".repeat(ProjectFiles.MAX_NAME_LENGTH)})
            assertNull(ProjectFiles.nameProblem(ok), ok);
    }

    @Test void rejectsBadNamesWithAReason() {
        assertEquals("Enter a name.", ProjectFiles.nameProblem(null));
        assertEquals("Enter a name.", ProjectFiles.nameProblem(""));
        assertEquals("Enter a name.", ProjectFiles.nameProblem("   "));
        assertTrue(ProjectFiles.nameProblem(".").contains("not a valid name"));
        assertTrue(ProjectFiles.nameProblem(" .. ").contains("not a valid name"));
        assertTrue(ProjectFiles.nameProblem("a/b").contains("/"));
        assertTrue(ProjectFiles.nameProblem("a\\b").contains("\\"));
        assertTrue(ProjectFiles.nameProblem("tab\there").contains("control"));
        assertTrue(ProjectFiles.nameProblem("nul\0").contains("control"));
        assertTrue(ProjectFiles.nameProblem("x".repeat(ProjectFiles.MAX_NAME_LENGTH + 1)).contains("255"));
    }

    // ---- where new things go ----

    @Test void newItemsGoInsideAFolderAndBesideAFile() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        Path file = Files.createFile(folder.resolve("A.java"));
        assertEquals(folder, ProjectFiles.directoryFor(folder));
        assertEquals(folder, ProjectFiles.directoryFor(file));
        assertEquals(project, ProjectFiles.directoryFor(project));
    }

    @Test void aRootPathHasNoParentSoItIsItsOwnDirectory() {
        Path root = dir.toAbsolutePath().getRoot();
        assertEquals(root, ProjectFiles.directoryFor(root.resolve("does-not-exist-" + System.nanoTime())).getRoot());
        assertEquals(root, ProjectFiles.directoryFor(root)); // a directory
    }

    // ---- create ----

    @Test void createsAnEmptyFile() throws IOException {
        Path created = ProjectFiles.createFile(project, "Main.java");
        assertEquals(project.resolve("Main.java"), created);
        assertTrue(Files.isRegularFile(created));
        assertEquals(0, Files.size(created));
    }

    @Test void createsAFolder() throws IOException {
        Path created = ProjectFiles.createFolder(project, "src");
        assertEquals(project.resolve("src"), created);
        assertTrue(Files.isDirectory(created));
    }

    @Test void surroundingSpacesInTheNameAreIgnored() throws IOException {
        assertEquals(project.resolve("a.txt"), ProjectFiles.createFile(project, "  a.txt "));
        assertEquals(project.resolve("b"), ProjectFiles.createFolder(project, " b "));
    }

    @Test void neverOverwritesAnExistingFile() throws IOException {
        Path f = project.resolve("keep.txt");
        Files.writeString(f, "precious");
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "keep.txt"));
        assertTrue(ex.getMessage().contains("keep.txt") && ex.getMessage().contains("already exists"), ex.getMessage());
        assertEquals("precious", Files.readString(f));
    }

    @Test void neverReplacesAFolderWithAFileOrTheOtherWayRound() throws IOException {
        Files.createDirectory(project.resolve("thing"));
        Files.writeString(project.resolve("other"), "x");
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "thing"));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "thing"));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "other"));
        assertEquals("x", Files.readString(project.resolve("other")));
    }

    @Test void aBrokenSymbolicLinkCountsAsExisting() {
        link(project.resolve("dangling"), project.resolve("nowhere"));
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "dangling"));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "dangling"));
        assertFalse(Files.exists(project.resolve("nowhere")));
    }

    @Test void refusesInvalidNamesWithoutCreatingAnything() throws IOException {
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, ""));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "../escape"));
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "a/b"));
        try (var s = Files.list(project)) { assertEquals(0, s.count()); }
        assertFalse(Files.exists(dir.resolve("escape")));
    }

    @Test void cannotCreateInsideAFileOrAMissingFolder() throws IOException {
        Path file = Files.createFile(project.resolve("f.txt"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.createFile(file, "x"));
        assertTrue(ex.getMessage().contains("f.txt") && ex.getMessage().contains("not a folder"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project.resolve("missing"), "x"));
    }

    // ---- contents ----

    @Test void countsWhatIsInsideAFolder() throws IOException {
        Path a = Files.createDirectories(project.resolve("a/b"));
        Files.createFile(a.resolve("1.txt"));
        Files.createFile(project.resolve("a/2.txt"));
        Files.createFile(project.resolve("3.txt"));
        assertEquals(new Contents(2, 1), ProjectFiles.contents(project.resolve("a")));
        assertEquals(new Contents(3, 2), ProjectFiles.contents(project));
        assertEquals(new Contents(1, 0), ProjectFiles.contents(project.resolve("3.txt")));
        assertEquals(new Contents(1, 0), ProjectFiles.contents(a)); // a is project/a/b
        assertEquals(new Contents(0, 0), ProjectFiles.contents(Files.createDirectory(project.resolve("empty"))));
    }

    @Test void describesContentsInWords() {
        assertEquals("", new Contents(0, 0).describe());
        assertEquals("1 file", new Contents(1, 0).describe());
        assertEquals("2 files", new Contents(2, 0).describe());
        assertEquals("1 folder", new Contents(0, 1).describe());
        assertEquals("3 folders", new Contents(0, 3).describe());
        assertEquals("1 file and 1 folder", new Contents(1, 1).describe());
        assertEquals("4 files and 2 folders", new Contents(4, 2).describe());
    }

    @Test void linksInsideAFolderAreCountedNotFollowed() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside/deep"));
        Files.createFile(outside.resolve("x.txt"));
        link(project.resolve("link"), dir.resolve("outside"));
        assertEquals(new Contents(1, 0), ProjectFiles.contents(project)); // the link is one entry, its target's files are not
    }

    // ---- isInside ----

    @Test void isInsideComparesPaths() {
        assertTrue(ProjectFiles.isInside(project, project));
        assertTrue(ProjectFiles.isInside(project, project.resolve("a/b.txt")));
        assertTrue(ProjectFiles.isInside(project, project.resolve("a/../b.txt")));
        assertFalse(ProjectFiles.isInside(project, project.resolve("../other.txt")));
        assertFalse(ProjectFiles.isInside(project.resolve("src"), project.resolve("src2/A.java"))); // a prefix of the text is not a parent
        assertFalse(ProjectFiles.isInside(project.resolve("src"), project));
    }

    // ---- delete ----

    @Test void deletesAFile() throws IOException {
        Path f = Files.createFile(project.resolve("gone.txt"));
        ProjectFiles.delete(project, f);
        assertFalse(Files.exists(f));
        assertTrue(Files.isDirectory(project));
    }

    @Test void deletesAnEmptyFolder() throws IOException {
        Path d = Files.createDirectory(project.resolve("empty"));
        ProjectFiles.delete(project, d);
        assertFalse(Files.exists(d));
    }

    @Test void deletesAFolderWithEverythingInIt() throws IOException {
        Path deep = Files.createDirectories(project.resolve("a/b/c"));
        Files.writeString(deep.resolve("x.txt"), "x");
        Files.writeString(project.resolve("a/y.txt"), "y");
        Files.writeString(project.resolve("sibling.txt"), "stay");
        ProjectFiles.delete(project, project.resolve("a"));
        assertFalse(Files.exists(project.resolve("a")));
        assertEquals("stay", Files.readString(project.resolve("sibling.txt")));
    }

    @Test void multipleSelectedFilesAndFoldersCanBeDeletedTogether() throws IOException {
        Path folder = Files.createDirectories(project.resolve("src/nested"));
        Path nestedFile = Files.createFile(folder.resolve("Main.java"));
        Path otherFile = Files.createFile(project.resolve("notes.txt"));
        Path otherFolder = Files.createDirectory(project.resolve("assets"));
        Files.createFile(otherFolder.resolve("logo.png"));

        for (Path target : ProjectFiles.topLevelTargets(List.of(nestedFile, otherFile, project.resolve("src"), otherFolder)))
            ProjectFiles.delete(project, target);

        assertFalse(Files.exists(project.resolve("src")));
        assertFalse(Files.exists(otherFile));
        assertFalse(Files.exists(otherFolder));
        assertTrue(Files.isDirectory(project), "the project root is preserved");
    }

    @Test void selectedChildrenAreOmittedWhenTheirFolderIsAlsoSelected() {
        Path folder = project.resolve("src");
        Path nestedFile = folder.resolve("Main.java");
        Path sibling = project.resolve("notes.txt");

        assertEquals(List.of(sibling, folder), ProjectFiles.topLevelTargets(List.of(nestedFile, sibling, folder)));
    }

    @Test void deletesWhenGivenAnUnnormalizedPath() throws IOException {
        Files.createDirectories(project.resolve("a"));
        Path f = Files.createFile(project.resolve("a/f.txt"));
        ProjectFiles.delete(project, project.resolve("a/../a/f.txt"));
        assertFalse(Files.exists(f));
    }

    @Test void refusesToDeleteTheProjectFolderItself() throws IOException {
        Files.createFile(project.resolve("keep.txt"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.delete(project, project));
        assertTrue(ex.getMessage().contains("project folder itself"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, project.resolve("sub/..")));
        assertTrue(Files.exists(project.resolve("keep.txt")));
    }

    @Test void refusesToDeleteAnythingOutsideTheProject() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "safe");
        Path sibling = Files.createDirectory(dir.resolve("project-two"));
        Files.createFile(sibling.resolve("x"));
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, outside));
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, project.resolve("../outside.txt")));
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, sibling)); // shares a name prefix, is not inside
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, dir));
        assertEquals("safe", Files.readString(outside));
        assertTrue(Files.exists(sibling.resolve("x")));
    }

    @Test void reportsAFileThatIsAlreadyGone() {
        NoSuchFileException ex = assertThrows(NoSuchFileException.class, () -> ProjectFiles.delete(project, project.resolve("ghost.txt")));
        assertTrue(ex.getMessage().contains("ghost.txt"), ex.getMessage());
    }

    @Test void deletingALinkRemovesTheLinkAndNotWhatItPointsAt() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("link"), outside);
        ProjectFiles.delete(project, project.resolve("link"));
        assertFalse(Files.exists(project.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("safe", Files.readString(precious));
    }

    @Test void deletingAFolderThatContainsALinkLeavesTheLinkTargetAlone() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        Path folder = Files.createDirectory(project.resolve("folder"));
        link(folder.resolve("link"), outside);
        Files.createFile(folder.resolve("own.txt"));
        ProjectFiles.delete(project, folder);
        assertFalse(Files.exists(folder));
        assertEquals("safe", Files.readString(precious));
    }

    @Test void deletingThroughALinkThatLeadsOutsideIsRefused() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("link"), outside);
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.delete(project, project.resolve("link/precious.txt")));
        assertTrue(ex.getMessage().contains("not inside the project"), ex.getMessage());
        assertEquals("safe", Files.readString(precious));
    }

    @Test void aLinkWithinTheProjectIsFine() throws IOException {
        Path real = Files.createDirectories(project.resolve("real"));
        Path f = Files.writeString(real.resolve("f.txt"), "x");
        link(project.resolve("alias"), real);
        ProjectFiles.delete(project, project.resolve("alias/f.txt"));
        assertFalse(Files.exists(f));
    }

    @Test void createThenDeleteRoundTrip() throws IOException {
        Path folder = ProjectFiles.createFolder(project, "pkg");
        Path file = ProjectFiles.createFile(folder, "A.java");
        assertEquals(new Contents(1, 0), ProjectFiles.contents(folder));
        ProjectFiles.delete(project, file);
        assertEquals(new Contents(0, 0), ProjectFiles.contents(folder));
        ProjectFiles.delete(project, folder);
        try (var s = Files.list(project)) { assertEquals(0, s.count()); }
    }

    // ---- rename ----

    @Test void renamesAFileInPlaceKeepingItsContent() throws IOException {
        Path f = Files.writeString(project.resolve("Old.java"), "class Old {}");
        Path renamed = ProjectFiles.rename(project, f, "New.java");
        assertEquals(project.resolve("New.java"), renamed);
        assertFalse(Files.exists(f));
        assertEquals("class Old {}", Files.readString(renamed));
    }

    @Test void renamesAFolderWithEverythingInIt() throws IOException {
        Path folder = Files.createDirectories(project.resolve("old/deep"));
        Files.writeString(folder.resolve("x.txt"), "x");
        Path renamed = ProjectFiles.rename(project, project.resolve("old"), "fresh");
        assertEquals(project.resolve("fresh"), renamed);
        assertFalse(Files.exists(project.resolve("old")));
        assertEquals("x", Files.readString(project.resolve("fresh/deep/x.txt")));
    }

    @Test void renameIgnoresSurroundingSpacesAndUnnormalizedPaths() throws IOException {
        Files.createDirectories(project.resolve("a"));
        Path f = Files.createFile(project.resolve("a/f.txt"));
        Path renamed = ProjectFiles.rename(project, project.resolve("a/../a/f.txt"), "  g.txt ");
        assertEquals(project.resolve("a/g.txt"), renamed);
        assertFalse(Files.exists(f));
    }

    @Test void renameToTheSameNameDoesNothing() throws IOException {
        Path f = Files.writeString(project.resolve("same.txt"), "x");
        assertEquals(f, ProjectFiles.rename(project, f, "same.txt"));
        assertEquals("x", Files.readString(f));
    }

    @Test void renameNeverOverwritesAnExistingFileOrFolder() throws IOException {
        Path a = Files.writeString(project.resolve("a.txt"), "A");
        Files.writeString(project.resolve("b.txt"), "B");
        Files.createDirectory(project.resolve("dir"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.rename(project, a, "b.txt"));
        assertTrue(ex.getMessage().contains("b.txt") && ex.getMessage().contains("already exists"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.rename(project, a, "dir"));
        assertEquals("A", Files.readString(a));
        assertEquals("B", Files.readString(project.resolve("b.txt")));
    }

    @Test void renameToABrokenLinkNameCountsAsExisting() throws IOException {
        Path a = Files.writeString(project.resolve("a.txt"), "A");
        link(project.resolve("dangling"), project.resolve("nowhere"));
        assertThrows(IOException.class, () -> ProjectFiles.rename(project, a, "dangling"));
        assertEquals("A", Files.readString(a));
        assertFalse(Files.exists(project.resolve("nowhere")));
    }

    @Test void renameRefusesBadNamesAndLeavesTheFileAlone() throws IOException {
        Path f = Files.writeString(project.resolve("f.txt"), "x");
        for (String bad : new String[] {"", "  ", "..", "a/b", "a\\b", "tab\there"})
            assertThrows(IOException.class, () -> ProjectFiles.rename(project, f, bad), bad);
        assertEquals("x", Files.readString(f));
    }

    @Test void changingOnlyTheLetterCaseIsAllowed() throws IOException {
        Path f = Files.writeString(project.resolve("readme.md"), "x");
        Path renamed = ProjectFiles.rename(project, f, "README.md");
        assertEquals(project.resolve("README.md"), renamed);
        try (var names = Files.list(project)) { // exactly one entry, with the new spelling, on any file system
            assertEquals(java.util.List.of("README.md"), names.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test void caseOnlyRenameIsNotAnOverwriteOnACaseInsensitiveFileSystem() throws IOException {
        Path probe = Files.createFile(project.resolve("Probe"));
        assumeTrue(Files.exists(project.resolve("pROBE")), "this file system is case-sensitive");
        Path renamed = ProjectFiles.rename(project, probe, "pROBE");
        assertEquals("pROBE", renamed.getFileName().toString());
        try (var names = Files.list(project)) { assertEquals(java.util.List.of("pROBE"), names.map(p -> p.getFileName().toString()).toList()); }
    }

    @Test void renamingOverADifferentFileThatDiffersOnlyInCaseIsNeverAnOverwrite() throws IOException {
        Path a = Files.writeString(project.resolve("a.txt"), "A");
        Files.writeString(project.resolve("b.txt"), "B");
        try {
            ProjectFiles.rename(project, a, "B.TXT");
        } catch (IOException expectedOnCaseInsensitiveFileSystems) { /* B.TXT is b.txt there */ }
        assertEquals("B", Files.readString(project.resolve("b.txt"))); // b.txt's content survives on any file system
    }

    @Test void renameRefusesTheProjectFolderItself() throws IOException {
        Files.createFile(project.resolve("keep.txt"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.rename(project, project, "other"));
        assertTrue(ex.getMessage().contains("project folder itself"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.rename(project, project.resolve("sub/.."), "other"));
        assertTrue(Files.exists(project.resolve("keep.txt")));
    }

    @Test void renameRefusesAFileSystemRoot() {
        Path root = dir.toAbsolutePath().getRoot();
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.rename(project, root, "x"));
        assertTrue(ex.getMessage().contains("project folder itself"), ex.getMessage());
    }

    @Test void renameRefusesAnythingOutsideTheProject() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "safe");
        Path sibling = Files.createDirectory(dir.resolve("project-two"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.rename(project, outside, "renamed.txt"));
        assertTrue(ex.getMessage().contains("not inside the project"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.rename(project, sibling, "x")); // shares a name prefix, is not inside
        assertThrows(IOException.class, () -> ProjectFiles.rename(project, project.resolve("../outside.txt"), "renamed.txt"));
        assertEquals("safe", Files.readString(outside));
        assertFalse(Files.exists(dir.resolve("renamed.txt")));
    }

    @Test void renameReportsAFileThatIsAlreadyGone() {
        NoSuchFileException ex = assertThrows(NoSuchFileException.class, () -> ProjectFiles.rename(project, project.resolve("ghost.txt"), "x"));
        assertTrue(ex.getMessage().contains("ghost.txt"), ex.getMessage());
    }

    @Test void renamingALinkRenamesTheLinkAndNotWhatItPointsAt() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("link"), outside);
        Path renamed = ProjectFiles.rename(project, project.resolve("link"), "alias");
        assertTrue(Files.isSymbolicLink(renamed));
        assertFalse(Files.exists(project.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("safe", Files.readString(precious));
        assertTrue(Files.isDirectory(outside));
    }

    @Test void renameThroughALinkThatLeadsOutsideIsRefused() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("link"), outside);
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.rename(project, project.resolve("link/precious.txt"), "taken.txt"));
        assertTrue(ex.getMessage().contains("not inside the project"), ex.getMessage());
        assertEquals("safe", Files.readString(precious));
        assertFalse(Files.exists(outside.resolve("taken.txt")));
    }

    // ---- move ----

    @Test void movesAFileIntoAFolder() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        Path f = Files.writeString(project.resolve("A.java"), "class A {}");
        Path moved = ProjectFiles.moveInto(project, f, folder);
        assertEquals(folder.resolve("A.java"), moved);
        assertFalse(Files.exists(f));
        assertEquals("class A {}", Files.readString(moved));
    }

    @Test void movesAFolderWithEverythingInItIntoAnother() throws IOException {
        Files.createDirectories(project.resolve("pkg/sub"));
        Files.writeString(project.resolve("pkg/sub/x.txt"), "x");
        Path dest = Files.createDirectory(project.resolve("dest"));
        Path moved = ProjectFiles.moveInto(project, project.resolve("pkg"), dest);
        assertEquals(dest.resolve("pkg"), moved);
        assertEquals("x", Files.readString(project.resolve("dest/pkg/sub/x.txt")));
        assertFalse(Files.exists(project.resolve("pkg")));
    }

    @Test void movesBackUpToTheProjectFolder() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        Path f = Files.writeString(folder.resolve("A.java"), "x");
        assertEquals(project.resolve("A.java"), ProjectFiles.moveInto(project, f, project));
    }

    @Test void movingIntoTheFolderItIsAlreadyInDoesNothing() throws IOException {
        Path f = Files.writeString(project.resolve("A.java"), "x");
        assertEquals(f, ProjectFiles.moveInto(project, f, project));
        assertEquals(f, ProjectFiles.moveInto(project, project.resolve("./A.java"), project.resolve("sub/..")));
        assertEquals("x", Files.readString(f));
    }

    @Test void moveCanRenameAtTheSameTime() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        Path f = Files.writeString(project.resolve("A.java"), "x");
        assertEquals(folder.resolve("B.java"), ProjectFiles.move(project, f, folder, "B.java"));
        assertFalse(Files.exists(f));
    }

    @Test void moveNeverOverwrites() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        Files.writeString(folder.resolve("A.java"), "kept");
        Path f = Files.writeString(project.resolve("A.java"), "incoming");
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, f, folder));
        assertTrue(ex.getMessage().contains("A.java") && ex.getMessage().contains("already exists"), ex.getMessage());
        assertEquals("kept", Files.readString(folder.resolve("A.java")));
        assertEquals("incoming", Files.readString(f));
    }

    @Test void moveRefusesAFolderIntoItselfOrBelowItself() throws IOException {
        Path folder = Files.createDirectories(project.resolve("a/b"));
        Files.writeString(folder.resolve("x.txt"), "x");
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, project.resolve("a"), project.resolve("a")));
        assertTrue(ex.getMessage().contains("into itself"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, project.resolve("a"), folder));
        assertEquals("x", Files.readString(folder.resolve("x.txt")));
    }

    @Test void aFolderMayMoveIntoASiblingWhoseNameStartsTheSame() throws IOException {
        Files.createDirectory(project.resolve("a"));
        Path sibling = Files.createDirectory(project.resolve("a2"));
        assertEquals(sibling.resolve("a"), ProjectFiles.moveInto(project, project.resolve("a"), sibling));
    }

    @Test void moveRefusesTheProjectFolderAndAFileSystemRoot() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, project, folder));
        assertTrue(ex.getMessage().contains("project folder itself"), ex.getMessage());
        Path root = dir.toAbsolutePath().getRoot();
        ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, root, folder));
        assertTrue(ex.getMessage().contains("project folder itself"), ex.getMessage());
    }

    @Test void moveRefusesASourceOutsideTheProject() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "safe");
        assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, outside, project));
        assertEquals("safe", Files.readString(outside));
        assertFalse(Files.exists(project.resolve("outside.txt")));
    }

    @Test void moveRefusesADestinationOutsideTheProject() throws IOException {
        Path f = Files.writeString(project.resolve("f.txt"), "x");
        Path sibling = Files.createDirectory(dir.resolve("project-two"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, f, dir));
        assertTrue(ex.getMessage().contains("destination is not inside the project"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, f, sibling));
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(sibling.resolve("f.txt")));
    }

    @Test void moveRefusesADestinationThatIsNotAFolder() throws IOException {
        Path f = Files.writeString(project.resolve("f.txt"), "x");
        Path other = Files.writeString(project.resolve("other.txt"), "y");
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, f, other));
        assertTrue(ex.getMessage().contains("not a folder"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, f, project.resolve("missing")));
        assertEquals("x", Files.readString(f));
    }

    @Test void moveReportsASourceThatIsAlreadyGone() {
        NoSuchFileException ex = assertThrows(NoSuchFileException.class, () -> ProjectFiles.moveInto(project, project.resolve("ghost.txt"), project));
        assertTrue(ex.getMessage().contains("ghost.txt"), ex.getMessage());
    }

    @Test void moveRefusesABadName() throws IOException {
        Path f = Files.writeString(project.resolve("f.txt"), "x");
        assertThrows(IOException.class, () -> ProjectFiles.move(project, f, project, "a/b"));
        assertEquals("x", Files.readString(f));
    }

    @Test void moveIntoAFolderThroughALinkThatLeadsOutsideIsRefused() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path f = Files.writeString(project.resolve("f.txt"), "x");
        link(project.resolve("escape"), outside);
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, f, project.resolve("escape")));
        assertTrue(ex.getMessage().contains("destination is not inside the project"), ex.getMessage());
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(outside.resolve("f.txt")));
    }

    @Test void moveOfAFileReachedThroughALinkThatLeadsOutsideIsRefused() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("escape"), outside);
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.moveInto(project, project.resolve("escape/precious.txt"), project));
        assertTrue(ex.getMessage().contains("not inside the project"), ex.getMessage());
        assertEquals("safe", Files.readString(precious));
        assertFalse(Files.exists(project.resolve("precious.txt")));
    }

    @Test void movingALinkMovesTheLinkAndNotWhatItPointsAt() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        Path folder = Files.createDirectory(project.resolve("src"));
        link(project.resolve("link"), outside);
        Path moved = ProjectFiles.moveInto(project, project.resolve("link"), folder);
        assertTrue(Files.isSymbolicLink(moved));
        assertEquals("safe", Files.readString(precious));
        assertTrue(Files.isDirectory(outside));
    }

    @Test void aFolderLinkPointingAtItsOwnParentIsNotTreatedAsMovingIntoItself() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        link(folder.resolve("loop"), project); // src/loop -> project
        Path moved = ProjectFiles.moveInto(project, folder.resolve("loop"), project);
        assertTrue(Files.isSymbolicLink(moved));
        assertTrue(Files.isDirectory(folder));
    }

    // ---- relocated (repointing open tabs) ----

    @Test void relocatedMapsAFileBelowAMovedFolder() {
        Path from = project.resolve("old");
        Path to = project.resolve("src/new");
        assertEquals(project.resolve("src/new/a/B.java"), ProjectFiles.relocated(from, to, project.resolve("old/a/B.java")));
        assertEquals(to, ProjectFiles.relocated(from, to, from));
        assertEquals(project.resolve("src/new/B.java"), ProjectFiles.relocated(from, to, project.resolve("old/x/../B.java")));
    }

    @Test void relocatedIsNullForAnythingElse() {
        Path from = project.resolve("old");
        Path to = project.resolve("new");
        assertNull(ProjectFiles.relocated(from, to, project.resolve("older/B.java"))); // a prefix of the text is not a parent
        assertNull(ProjectFiles.relocated(from, to, project.resolve("B.java")));
        assertNull(ProjectFiles.relocated(from, to, project));
    }

    @Test void renameThenRelocatedAgreeOnWhereAnOpenFileWent() throws IOException {
        Path folder = Files.createDirectories(project.resolve("pkg/sub"));
        Path open = Files.writeString(folder.resolve("A.java"), "x");
        Path renamed = ProjectFiles.rename(project, project.resolve("pkg"), "lib");
        Path where = ProjectFiles.relocated(project.resolve("pkg"), renamed, open);
        assertEquals(project.resolve("lib/sub/A.java"), where);
        assertEquals("x", Files.readString(where));
    }

    // ---- the case-only rule, checked directly so it does not depend on the file system's case rules ----

    @Test void onlyCaseDiffersRecognisesTheSameEntryUnderADifferentSpelling() throws IOException {
        Path a = Files.writeString(project.resolve("x.txt"), "x");
        Path b = project.resolve("X.TXT");
        try { Files.createLink(b, a); } catch (IOException | UnsupportedOperationException | SecurityException ex) { assumeTrue(false, "hard links are not available here"); }
        assertTrue(ProjectFiles.onlyCaseDiffers(a, b)); // same entry, names differ only in case
        assertFalse(ProjectFiles.onlyCaseDiffers(a, a)); // identical spelling is not a case change
    }

    @Test void onlyCaseDiffersIsFalseForDifferentEntriesFoldersOrNames() throws IOException {
        Path a = Files.writeString(project.resolve("a.txt"), "A");
        Path other = Files.writeString(project.resolve("A.TXT.copy"), "B");
        Path upper = Files.writeString(project.resolve("A.TXT"), "C"); // a different file on a case-sensitive file system
        assertFalse(ProjectFiles.onlyCaseDiffers(a, other)); // different letters
        if (!Files.isSameFile(a, upper)) assertFalse(ProjectFiles.onlyCaseDiffers(a, upper)); // same letters, different entry
        Path folder = Files.createDirectory(project.resolve("sub"));
        Path inFolder = Files.writeString(folder.resolve("A.txt"), "D");
        assertFalse(ProjectFiles.onlyCaseDiffers(a, inFolder)); // different folders
    }
}
