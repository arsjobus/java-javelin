package com.javelin_ide.io;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The "Recent Projects" list: the last few project folders, newest first, kept in a {@link Preferences} node on the user's
 * own machine. JavaFX-free, so the rules are tested without a display.
 *
 * <p>Folders that no longer exist are skipped when reading, so a deleted project never shows up as a dead menu entry.
 */
public final class RecentProjects {
    /** How many projects are remembered. */
    public static final int MAX = 8;

    private static final String KEY_PREFIX = "recent.project.";

    private final Preferences store;

    public RecentProjects(Preferences store) {
        this.store = store;
    }

    /** Absolute paths of the remembered projects that still exist, newest first. */
    public List<String> list() {
        List<String> projects = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            String path = store.get(KEY_PREFIX + i, "");
            if (!path.isBlank() && new File(path).isDirectory()) projects.add(path);
        }
        return projects;
    }

    /** Moves {@code directory} to the front, drops the oldest entry beyond {@link #MAX}, and forgets duplicates. */
    public void add(File directory) {
        List<String> projects = list();
        String path = directory.getAbsolutePath();
        projects.remove(path);
        projects.add(0, path);
        for (int i = 0; i < MAX; i++) {
            if (i < projects.size()) store.put(KEY_PREFIX + i, projects.get(i));
            else store.remove(KEY_PREFIX + i);
        }
    }

    /** Forgets every remembered project. */
    public void clear() {
        for (int i = 0; i < MAX; i++) store.remove(KEY_PREFIX + i);
    }
}
