package com.javelin_ide.io;

import java.io.File;
import java.util.Objects;

/** Project-switch behavior that can be checked without starting the JavaFX UI. */
public final class ProjectChange {
    private ProjectChange() {}

    /** Closes project-scoped sessions when switching from no project or to a different directory. */
    public static void closeSessionsIfChanged(File current, File next, Runnable closeSessions) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(closeSessions, "closeSessions");
        if (current == null || !normalize(current).equals(normalize(next))) closeSessions.run();
    }

    private static java.nio.file.Path normalize(File directory) {
        return directory.toPath().toAbsolutePath().normalize();
    }
}
