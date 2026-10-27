package com.javelin_ide.io;

import java.io.File;
import java.net.URI;

/** Small path conversions shared by the status bar, the Problems panel and language-server results. JavaFX-free. */
public final class ProjectPaths {
    private ProjectPaths() {}

    /**
     * How to show {@code file} to a person: relative to the project when it is inside one, otherwise as given. A
     * {@code null} project (nothing open) always gives the plain path.
     */
    public static String display(File project, File file) {
        if (project != null && file.toPath().startsWith(project.toPath())) return project.toPath().relativize(file.toPath()).toString();
        return file.getPath();
    }

    /** The local file a {@code file:} URI names, or {@code null} for other schemes and malformed file URIs. */
    public static File fromUri(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
        try {
            return new File(uri);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
