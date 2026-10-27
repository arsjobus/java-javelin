package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentProjectsTest {
    @TempDir Path root;

    /** An in-memory preferences node so the tests never touch the real user store. */
    private static final class MemoryPreferences extends AbstractPreferences {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        MemoryPreferences() {
            super(null, "");
        }

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

    private File folder(String name) throws IOException {
        return Files.createDirectories(root.resolve(name)).toFile();
    }

    @Test void startsEmpty() {
        assertEquals(List.of(), new RecentProjects(new MemoryPreferences()).list());
    }

    @Test void newestComesFirstAndRepeatsMoveToTheFront() throws IOException {
        RecentProjects recent = new RecentProjects(new MemoryPreferences());
        File a = folder("a"), b = folder("b"), c = folder("c");
        recent.add(a);
        recent.add(b);
        recent.add(c);
        assertEquals(List.of(c.getAbsolutePath(), b.getAbsolutePath(), a.getAbsolutePath()), recent.list());
        recent.add(a);
        assertEquals(List.of(a.getAbsolutePath(), c.getAbsolutePath(), b.getAbsolutePath()), recent.list());
    }

    @Test void remembersOnlyTheNewestFew() throws IOException {
        RecentProjects recent = new RecentProjects(new MemoryPreferences());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < RecentProjects.MAX + 3; i++) {
            File dir = folder("p" + i);
            recent.add(dir);
            expected.add(0, dir.getAbsolutePath());
        }
        assertEquals(expected.subList(0, RecentProjects.MAX), recent.list());
    }

    @Test void foldersThatNoLongerExistDisappearFromTheList() throws IOException {
        RecentProjects recent = new RecentProjects(new MemoryPreferences());
        File keep = folder("keep"), gone = folder("gone");
        recent.add(gone);
        recent.add(keep);
        Files.delete(gone.toPath());
        assertEquals(List.of(keep.getAbsolutePath()), recent.list());
    }

    @Test void aSurvivingProjectIsNotDuplicatedAfterAnEarlierOneVanished() throws IOException {
        Preferences store = new MemoryPreferences();
        RecentProjects recent = new RecentProjects(store);
        File a = folder("a"), b = folder("b");
        recent.add(a);
        recent.add(b);
        Files.delete(b.toPath());
        recent.add(a);
        assertEquals(List.of(a.getAbsolutePath()), recent.list());
        assertEquals("", store.get("recent.project.1", ""), "stale slots are cleared");
    }
}
