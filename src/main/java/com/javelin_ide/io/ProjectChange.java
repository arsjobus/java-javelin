package com.javelin_ide.io;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Project-switch behavior that can be checked without starting the JavaFX UI. */
public final class ProjectChange {
    private ProjectChange() {}

    /** Closes project-scoped sessions when switching from no project or to a different directory. */
    public static void closeSessionsIfChanged(File current, File next, Runnable closeSessions) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(closeSessions, "closeSessions");
        if (current == null || !normalize(current).equals(normalize(next))) closeSessions.run();
    }

    /** True when opening {@code next} replaces a different, already open project (so its files must be dealt with first). */
    public static boolean replacesOpenProject(File current, File next) {
        Objects.requireNonNull(next, "next");
        return current != null && !normalize(current).equals(normalize(next));
    }

    /**
     * Closes {@code items} one by one, in order. Each item is first offered to {@code mayClose}, which is where the user
     * saves or discards it (or refuses); only an accepted item is passed to {@code close}. Stops at the first refusal and
     * returns false, leaving that item and every later one untouched; items already closed stay closed.
     * Returns true when every item was closed.
     */
    public static <T> boolean closeAllInOrder(List<T> items, Predicate<T> mayClose, Consumer<T> close) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(mayClose, "mayClose");
        Objects.requireNonNull(close, "close");
        for (T item : List.copyOf(items)) {
            if (!mayClose.test(item)) return false;
            close.accept(item);
        }
        return true;
    }

    private static java.nio.file.Path normalize(File directory) {
        return directory.toPath().toAbsolutePath().normalize();
    }
}
