package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.editor.Symbols;
import com.javelin_ide.editor.TextSearch;
import com.javelin_ide.io.FileIO;
import com.javelin_ide.io.ProjectFiles;
import com.javelin_ide.io.ProjectFinder;
import com.javelin_ide.io.ProjectPaths;
import com.javelin_ide.io.ProjectWatcher;
import com.javelin_ide.io.RecentProjects;
import com.javelin_ide.io.RecoveryStore;
import com.javelin_ide.io.Trash;
import com.javelin_ide.settings.NameList;
import com.javelin_ide.settings.Settings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person's day with the editor, driven through the same JavaFX-free services the window uses: open a project, create
 * and edit files, find things, rename and delete, survive a crash. Each test is one feature from FEATURES.md and states
 * the outcome a user would notice, so a failure here reads as "this feature no longer works".
 */
class ProjectWorkflowE2ETest {
    @TempDir Path home;
    private Trash.Bin bin;
    Path project;
    final AtomicInteger treeRefreshes = new AtomicInteger();
    ProjectWatcher watcher;
    Settings settings;

    /** In-memory stand-in for the user's preferences node. */
    private static final class MemoryPreferences extends AbstractPreferences {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();
        MemoryPreferences() { super(null, ""); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() {}
        @Override protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        @Override protected String[] childrenNamesSpi() { return new String[0]; }
        @Override protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException(); }
        @Override protected void syncSpi() throws BackingStoreException {}
        @Override protected void flushSpi() throws BackingStoreException {}
    }

    @BeforeEach void openProject() throws IOException {
        project = Files.createDirectories(home.resolve("demo"));
        bin = Trash.freedesktop(home.resolve("trash"), java.time.Clock.systemUTC());
        settings = new Settings(home.resolve("user").resolve(Settings.FILE_NAME));
        settings.setProject(project);
        settings.reload();
        Set<String> excludes = NameList.parse(settings.get("tree.exclude", ".git,target"));
        watcher = new ProjectWatcher(() -> excludes, treeRefreshes::incrementAndGet);
        watcher.watch(project);
    }

    @AfterEach void closeProject() { watcher.close(); }

    @Test void createEditSaveAndReopenAFile() throws IOException {
        Path src = ProjectFiles.createFolder(project, "src");
        Path main = ProjectFiles.createFile(src, "Main.java");
        assertEquals("", FileIO.readText(main), "a new file is empty");

        FileIO.writeAtomic(main, "class Main {\n    int count;\n}\n");
        assertEquals("class Main {\n    int count;\n}\n", FileIO.readText(main));

        assertThrows(IOException.class, () -> ProjectFiles.createFile(src, "Main.java"), "creating never overwrites");
        assertEquals("class Main {\n    int count;\n}\n", FileIO.readText(main), "the existing file is untouched");
    }

    @Test void findAndReplaceAcrossASavedFile() throws IOException {
        Path file = ProjectFiles.createFile(project, "Notes.txt");
        FileIO.writeAtomic(file, "Todo: fix todo list, TODO later");
        String text = FileIO.readText(file);

        assertEquals(3, TextSearch.count(text, "todo", true));
        assertEquals(1, TextSearch.count(text, "todo", false));
        FileIO.writeAtomic(file, TextSearch.replace(text, "todo", "done", true));
        assertEquals("done: fix done list, done later", FileIO.readText(file));
    }

    @Test void goToFileFindsFilesButSkipsExcludedFolders() throws IOException {
        Path nested = ProjectFiles.createFolder(ProjectFiles.createFolder(project, "src"), "util");
        Path helper = ProjectFiles.createFile(nested, "Helper.java");
        Path built = ProjectFiles.createFile(ProjectFiles.createFolder(project, "target"), "Helper.class");

        Set<String> excludes = NameList.parse(settings.get("tree.exclude", ".git,target"));
        assertEquals(helper.toFile(), ProjectFinder.find(project.toFile(), "Helper.java", excludes).orElseThrow());
        assertTrue(ProjectFinder.find(project.toFile(), "Helper.class", excludes).isEmpty(), "build output is not searched");
        assertTrue(Files.exists(built), "...but it is still there");
    }

    @Test void aProjectCanChangeWhatTheTreeHides() throws IOException {
        Files.createDirectories(project.resolve(Settings.PROJECT_DIRECTORY));
        Files.writeString(settings.projectFile(), "tree.exclude=build, .cache\n");
        settings.reload();
        assertEquals(List.of("build", ".cache"), List.copyOf(NameList.parse(settings.get("tree.exclude", ".git,target"))));
    }

    @Test void renamingAndMovingKeepsTheFileAndShowsRelativePaths() throws IOException {
        Path file = ProjectFiles.createFile(project, "Old.java");
        FileIO.writeAtomic(file, "class Old {}");
        Path folder = ProjectFiles.createFolder(project, "model");

        Path renamed = ProjectFiles.rename(project, file, "New.java");
        Path moved = ProjectFiles.moveInto(project, renamed, folder);

        assertEquals("class Old {}", FileIO.readText(moved));
        assertFalse(Files.exists(file));
        assertEquals(Path.of("model", "New.java").toString(), ProjectPaths.display(project.toFile(), moved.toFile()));
    }

    @Test void deletingIsConfinedToTheProjectAndGoesToTheBin() throws IOException {
        Path file = ProjectFiles.createFile(project, "Scratch.txt");
        Path outside = Files.writeString(home.resolve("precious.txt"), "keep");

        assertThrows(IOException.class, () -> ProjectFiles.trash(project, outside, bin));
        assertThrows(IOException.class, () -> ProjectFiles.trash(project, project, bin), "the project folder itself is refused");
        ProjectFiles.trash(project, file, bin);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(home.resolve("trash/files/Scratch.txt")), "deleted files can be restored from the bin");
        assertEquals("keep", Files.readString(outside));
    }

    @Test void changesMadeOutsideTheEditorRefreshTheTree() throws IOException {
        assertFalse(watcher.poll(), "nothing changed yet");
        Files.writeString(project.resolve("FromTerminal.txt"), "x");
        assertTrue(watcher.poll());
        assertEquals(1, treeRefreshes.get());

        Path ours = ProjectFiles.createFile(project, "Ours.txt");
        watcher.acknowledge();
        assertFalse(watcher.poll(), "the editor's own change, once acknowledged, does not loop back");
        Files.delete(ours);
        assertTrue(watcher.poll());
    }

    @Test void recentProjectsRememberWhatWasOpened() throws IOException {
        RecentProjects recent = new RecentProjects(new MemoryPreferences());
        File other = Files.createDirectories(home.resolve("other")).toFile();
        recent.add(project.toFile());
        recent.add(other);
        assertEquals(List.of(other.getAbsolutePath(), project.toFile().getAbsolutePath()), recent.list());
    }

    @Test void unsavedWorkSurvivesACrashAndIsGoneOnceSaved() throws IOException {
        Path recoveryDir = home.resolve("recovery");
        Path file = ProjectFiles.createFile(project, "Draft.java");
        FileIO.writeAtomic(file, "class Draft {}");

        RecoveryStore running = new RecoveryStore(recoveryDir);
        String id = RecoveryStore.newId();
        assertTrue(running.saveIfChanged(id, file, "class Draft { int unsaved; }", 12, 0));
        // the process dies here: no endSession(), no discard()

        RecoveryStore nextRun = new RecoveryStore(recoveryDir);
        RecoveryStore.Snapshot found = nextRun.pending().get(0);
        assertEquals("class Draft { int unsaved; }", found.text());
        assertEquals(file.toAbsolutePath(), found.original().toAbsolutePath());
        assertEquals("class Draft {}", FileIO.readText(file), "the file on disk was never touched by recovery");

        FileIO.writeAtomic(file, found.text());
        nextRun.discard(found.id());
        assertTrue(nextRun.pending().isEmpty());
    }

    @Test void symbolLookupFollowsTheCaretLikeHoverAndGoToDefinitionDo() {
        String code = "int total = add(total, 1);";
        int caretAfterAdd = code.indexOf('(');
        assertEquals("add", Symbols.wordAt(code, Symbols.symbolOffset(code, caretAfterAdd)));
        assertEquals("", Symbols.wordAt(code, Symbols.symbolOffset(code, code.indexOf(", ") + 1)));
    }
}
