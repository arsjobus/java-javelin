package com.javelin_ide.io;

import java.io.File;
import java.util.Optional;
import java.util.Set;

/** "Go to File": finds a file by its exact name anywhere inside a project. JavaFX-free. */
public final class ProjectFinder {
    private ProjectFinder() {}

    /**
     * Looks for a file called {@code name} in {@code directory}. Files directly in a folder win over files in its
     * sub-folders, and folders named in {@code excludedFolders} (such as {@code .git} or {@code target}) are never entered.
     * Unreadable folders are skipped rather than reported.
     */
    public static Optional<File> find(File directory, String name, Set<String> excludedFolders) {
        File[] children = directory.listFiles();
        if (children == null) return Optional.empty();
        for (File child : children) if (child.isFile() && child.getName().equals(name)) return Optional.of(child);
        for (File child : children) {
            if (!child.isDirectory() || excludedFolders.contains(child.getName())) continue;
            Optional<File> hit = find(child, name, excludedFolders);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }
}
