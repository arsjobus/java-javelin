package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrashTest {
    @TempDir Path dir;
    Path trash;
    Trash.Bin freedesktop;

    @BeforeEach void setUp() {
        trash = dir.resolve("Trash");
        freedesktop = Trash.freedesktop(trash, Clock.fixed(Instant.parse("2026-10-31T09:05:07Z"), ZoneOffset.UTC));
    }

    private Path file(String name, String text) throws IOException { return Files.writeString(dir.resolve(name), text); }

    // ---- names and the choice of bin ----

    @Test void theBinIsCalledRecycleBinOnWindowsAndTrashEverywhereElse() {
        assertEquals("Recycle Bin", Trash.name("Windows 11"));
        assertEquals("Trash", Trash.name("Mac OS X"));
        assertEquals("Trash", Trash.name("Darwin"), "Darwin contains the letters win but is not Windows");
        assertEquals("Trash", Trash.name("Linux"));
        assertNotNull(Trash.name());
    }

    @Test void everySystemWithoutAnOwnTrashGetsAFolderBasedFallback() {
        assertNull(Trash.fallbackFor("Windows 11", dir, null), "Windows has no folder to fall back to; the system Recycle Bin or nothing");
        assertNotNull(Trash.fallbackFor("Mac OS X", dir, null));
        assertNotNull(Trash.fallbackFor("Darwin", dir, null));
        assertNotNull(Trash.fallbackFor("Linux", dir, null));
    }

    @Test void linuxFallbackFollowsXdgDataHomeAndOtherwiseTheHomeFolder() throws IOException {
        Path custom = dir.resolve("custom-data");
        Trash.fallbackFor("Linux", dir, custom.toString()).moveToTrash(file("a.txt", "a"));
        assertTrue(Files.exists(custom.resolve("Trash/files/a.txt")));

        for (String unset : new String[] {null, "", "  "}) {
            Path home = Files.createDirectories(dir.resolve("home-" + unset));
            Trash.fallbackFor("Linux", home, unset).moveToTrash(file("b-" + unset + ".txt", "b"));
            assertTrue(Files.exists(home.resolve(".local/share/Trash/files/b-" + unset + ".txt")), "unset XDG_DATA_HOME = '" + unset + "'");
        }
    }

    @Test void macFallbackUsesTheTrashFolderInTheHomeDirectory() throws IOException {
        Trash.fallbackFor("Mac OS X", dir, null).moveToTrash(file("m.txt", "m"));
        assertEquals("m", Files.readString(dir.resolve(".Trash/m.txt")));
    }

    @Test void theSystemBinIsAlwaysAvailableToAskAndNeverFailsToBeBuilt() { assertNotNull(Trash.system()); }

    // ---- combining the operating system's trash with the fallback ----

    @Test void theOperatingSystemsTrashComesFirst() throws IOException {
        List<String> calls = new ArrayList<>();
        Trash.combine("Linux", p -> calls.add("desktop"), p -> calls.add("fallback")).moveToTrash(dir.resolve("x"));
        assertEquals(List.of("desktop"), calls);
    }

    @Test void whenTheOperatingSystemRefusesTheFallbackIsUsed() throws IOException {
        List<String> calls = new ArrayList<>();
        Trash.combine("Linux", p -> { throw new IOException("no"); }, p -> calls.add("fallback")).moveToTrash(dir.resolve("x"));
        Trash.combine("Linux", p -> { throw new UnsupportedOperationException("no"); }, p -> calls.add("fallback")).moveToTrash(dir.resolve("x"));
        assertEquals(List.of("fallback", "fallback"), calls);
    }

    @Test void withoutAnOperatingSystemTrashTheFallbackIsUsed() throws IOException {
        List<String> calls = new ArrayList<>();
        Trash.combine("Linux", null, p -> calls.add("fallback")).moveToTrash(dir.resolve("x"));
        assertEquals(List.of("fallback"), calls);
    }

    @Test void withNoBinAtAllNothingIsDeletedAndTheReasonIsGiven() throws IOException {
        Path keep = file("keep.txt", "keep");
        IOException unavailable = assertThrows(IOException.class, () -> Trash.combine("Windows 11", null, null).moveToTrash(keep));
        assertTrue(unavailable.getMessage().contains("Recycle Bin") && unavailable.getMessage().contains("Nothing was deleted"), unavailable.getMessage());
        IOException refused = assertThrows(IOException.class, () -> Trash.combine("Windows 11", p -> { throw new IOException("access denied"); }, null).moveToTrash(keep));
        assertTrue(refused.getMessage().contains("access denied") && refused.getMessage().contains("Nothing was deleted"), refused.getMessage());
        assertEquals("keep", Files.readString(keep));
    }

    // ---- freedesktop.org layout ----

    @Test void anEntryMovesToFilesAndGetsAnInfoRecordThatAllowsRestoring() throws IOException {
        Path f = file("note.txt", "hello");
        freedesktop.moveToTrash(f);
        assertFalse(Files.exists(f));
        assertEquals("hello", Files.readString(trash.resolve("files/note.txt")));
        assertEquals("[Trash Info]\nPath=" + Trash.encode(f.toString()) + "\nDeletionDate=2026-10-31T09:05:07\n", Files.readString(trash.resolve("info/note.txt.trashinfo")));
    }

    @Test void aFolderMovesWithEverythingInIt() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("pkg/sub"));
        Files.writeString(folder.resolve("A.java"), "class A {}");
        freedesktop.moveToTrash(dir.resolve("pkg"));
        assertFalse(Files.exists(dir.resolve("pkg")));
        assertEquals("class A {}", Files.readString(trash.resolve("files/pkg/sub/A.java")));
    }

    @Test void sameNamesNeverOverwriteEachOther() throws IOException {
        for (String text : new String[] {"one", "two", "three"}) freedesktop.moveToTrash(file("dup.txt", text));
        assertEquals("one", Files.readString(trash.resolve("files/dup.txt")));
        assertEquals("two", Files.readString(trash.resolve("files/dup.txt.2")));
        assertEquals("three", Files.readString(trash.resolve("files/dup.txt.3")));
        assertTrue(Files.exists(trash.resolve("info/dup.txt.3.trashinfo")));
    }

    @Test void anEntryAlreadyInTheBinWithoutAnInfoRecordIsLeftAlone() throws IOException {
        Files.createDirectories(trash.resolve("files"));
        Files.writeString(trash.resolve("files/orphan.txt"), "old");
        freedesktop.moveToTrash(file("orphan.txt", "new"));
        assertEquals("old", Files.readString(trash.resolve("files/orphan.txt")));
        assertEquals("new", Files.readString(trash.resolve("files/orphan.txt.2")));
        assertFalse(Files.exists(trash.resolve("info/orphan.txt.trashinfo")), "no info record is left for a name that was not used");
    }

    @Test void whenTheMoveFailsTheEntryStaysAndNoInfoRecordIsLeftBehind() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("full"));
        Path ghost = dir.resolve("ghost.txt"); // does not exist, so the move fails
        IOException ex = assertThrows(IOException.class, () -> freedesktop.moveToTrash(ghost));
        assertTrue(ex.getMessage().contains("Nothing was deleted"), ex.getMessage());
        assertFalse(Files.exists(trash.resolve("info/ghost.txt.trashinfo")));
        assertTrue(Files.isDirectory(folder));
    }

    @Test void pathsInTheInfoRecordAreEncodedAsTheSpecificationRequires() {
        assertEquals("/home/a%20b/%C3%BC%25.txt", Trash.encode("/home/a b/ü%.txt"));
        assertEquals("/safe-chars_.~/x", Trash.encode("/safe-chars_.~/x"));
        assertEquals("C%3A/Users/me/a.txt", Trash.encode("C:\\Users\\me\\a.txt"), "Windows separators become slashes");
    }

    // ---- a folder as the bin (macOS) ----

    @Test void aFolderBinTakesFilesAndFoldersAndKeepsDuplicates() throws IOException {
        Path bin = dir.resolve("bin");
        Trash.Bin folderBin = Trash.userTrashFolder(bin);
        folderBin.moveToTrash(file("same.txt", "one"));
        folderBin.moveToTrash(file("same.txt", "two"));
        Files.createDirectories(dir.resolve("d/e"));
        Files.writeString(dir.resolve("d/e/f.txt"), "deep");
        folderBin.moveToTrash(dir.resolve("d"));
        assertEquals("one", Files.readString(bin.resolve("same.txt")));
        assertEquals("two", Files.readString(bin.resolve("same.txt 2")));
        assertEquals("deep", Files.readString(bin.resolve("d/e/f.txt")));
    }

    @Test void aFolderBinThatCannotMoveTheEntryLeavesItAlone() {
        IOException ex = assertThrows(IOException.class, () -> Trash.userTrashFolder(dir.resolve("bin")).moveToTrash(dir.resolve("ghost.txt")));
        assertTrue(ex.getMessage().contains("Nothing was deleted"), ex.getMessage());
    }
}
