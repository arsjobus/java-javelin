package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin_ide.io.FileIO;
import com.javelin_ide.io.ProjectChange;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Switching projects: the previous project's open files must each be saved or discarded (per unsaved file) and closed
 * before the new project opens. A JavaFX-free model of the window's tab list drives the same {@link ProjectChange} rules
 * that {@code App} uses.
 */
class ProjectSwitchE2ETest {
    /** An open editor tab: a file, its on-disk text and its unsaved text (null when clean). */
    private static final class OpenFile {
        final Path path; String unsaved;
        OpenFile(Path path, String unsaved) { this.path = path; this.unsaved = unsaved; }
        boolean dirty() { return unsaved != null; }
    }

    private enum Choice { SAVE, DISCARD, CANCEL }

    /** What {@code App.openProjectDirectory} does: returns the project now open (the old one when the switch is refused). */
    private static File switchProject(File current, File next, List<OpenFile> tabs, Predicate<OpenFile> confirm) {
        if (ProjectChange.replacesOpenProject(current, next)
                && !ProjectChange.closeAllInOrder(tabs, confirm, tabs::remove)) return current;
        return next;
    }

    private static Predicate<OpenFile> user(List<Choice> answers, List<String> asked) {
        List<Choice> remaining = new ArrayList<>(answers);
        return tab -> {
            if (!tab.dirty()) return true; // clean tabs close without a prompt
            asked.add(tab.path.getFileName().toString());
            Choice choice = remaining.remove(0);
            if (choice == Choice.CANCEL) return false;
            if (choice == Choice.SAVE) {
                try { FileIO.writeAtomic(tab.path, tab.unsaved); } catch (IOException e) { throw new AssertionError(e); }
            }
            tab.unsaved = null;
            return true;
        };
    }

    @Test
    void switchingWaitsForEachUnsavedFileThenClosesEverything(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "old-a"), b = Files.writeString(dir.resolve("B.java"), "old-b");
        Path c = Files.writeString(dir.resolve("C.java"), "clean");
        List<OpenFile> tabs = new ArrayList<>(List.of(new OpenFile(a, "new-a"), new OpenFile(b, "new-b"), new OpenFile(c, null)));
        List<String> asked = new ArrayList<>();
        File first = dir.resolve("one").toFile(), second = dir.resolve("two").toFile();

        File now = switchProject(first, second, tabs, user(List.of(Choice.SAVE, Choice.DISCARD), asked));

        assertEquals(second, now);
        assertTrue(tabs.isEmpty(), "every file of the previous project is closed");
        assertEquals(List.of("A.java", "B.java"), asked, "only the unsaved files were asked about, one by one");
        assertEquals("new-a", Files.readString(a), "Save wrote the file");
        assertEquals("old-b", Files.readString(b), "Discard left the file as it was on disk");
    }

    @Test
    void cancelOnAnUnsavedFileKeepsTheOldProjectAndItsRemainingFiles(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "old-a"), b = Files.writeString(dir.resolve("B.java"), "old-b");
        OpenFile dirtyB = new OpenFile(b, "new-b");
        List<OpenFile> tabs = new ArrayList<>(List.of(new OpenFile(a, "new-a"), dirtyB));
        File first = dir.resolve("one").toFile(), second = dir.resolve("two").toFile();

        File now = switchProject(first, second, tabs, user(List.of(Choice.DISCARD, Choice.CANCEL), new ArrayList<>()));

        assertEquals(first, now, "the new project did not open");
        assertEquals(List.of(dirtyB), tabs, "the file that was cancelled is still open");
        assertTrue(dirtyB.dirty(), "and its unsaved text is untouched");
        assertEquals("old-b", Files.readString(b));
    }

    @Test
    void reopeningTheSameProjectLeavesOpenFilesAlone(@TempDir Path dir) {
        OpenFile dirty = new OpenFile(dir.resolve("A.java"), "unsaved");
        List<OpenFile> tabs = new ArrayList<>(List.of(dirty));
        File project = dir.toFile();

        File now = switchProject(project, new File(project, "."), tabs, tab -> { throw new AssertionError("no prompt expected"); });

        assertFalse(tabs.isEmpty());
        assertTrue(dirty.dirty());
        assertEquals(new File(project, "."), now);
    }
}
