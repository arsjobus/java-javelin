package com.javelin.io;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The project tree's Copy, Cut and Paste, without any window: what was copied or cut, and what pasting it into a
 * folder does. JavaFX-free, so the rules are tested without a display.
 *
 * <p>Copy leaves the originals alone and can be pasted again and again (each paste makes new, never overwriting,
 * copies). Cut takes the originals out of their folder when they are pasted, so open tabs can follow them; whatever
 * could not be moved stays on the clipboard to try again somewhere else. Everything goes through {@link ProjectFiles},
 * so nothing leaves the project and nothing is overwritten.
 */
public final class FileClipboard {
    public enum Mode { COPY, CUT }

    /** One pasted item: where it came from and where it is now. {@code moved} is true for cut-and-paste. */
    public record Pasted(Path from, Path to, boolean moved) {}

    /**
     * What a paste did. {@code unchanged} counts cut items that were pasted into the folder they already were in;
     * {@code failures} are sentences naming the item, ready for an error dialog.
     */
    public record Result(List<Pasted> done, List<String> failures, int unchanged) {}

    private Mode mode = Mode.COPY;
    private List<Path> sources = List.of();

    /** Remembers {@code selected} for copying. Items already covered by a selected folder are left out. */
    public void copy(Collection<Path> selected) { remember(Mode.COPY, selected); }

    /** Remembers {@code selected} for moving at the next paste. Items already covered by a selected folder are left out. */
    public void cut(Collection<Path> selected) { remember(Mode.CUT, selected); }

    private void remember(Mode newMode, Collection<Path> selected) {
        mode = newMode;
        sources = ProjectFiles.topLevelTargets(selected);
    }

    public boolean isEmpty() { return sources.isEmpty(); }

    public Mode mode() { return mode; }

    /** What is waiting to be pasted, as absolute normalized paths. */
    public List<Path> sources() { return sources; }

    public void clear() { sources = List.of(); }

    /**
     * Pastes everything on the clipboard into {@code directory}. Each item succeeds or fails on its own, so one
     * refusal (a name that is taken, a folder into itself) does not stop the rest. After a cut, moved items leave the
     * clipboard, and so do items that no longer exist; refused ones stay so the person can paste them elsewhere.
     */
    public Result paste(Path projectRoot, Path directory) {
        List<Pasted> done = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Path> keep = new ArrayList<>();
        int unchanged = 0;
        for (Path source : sources) {
            try {
                if (mode == Mode.CUT) {
                    Path moved = ProjectFiles.moveInto(projectRoot, source, directory);
                    if (moved.equals(source)) unchanged++; else done.add(new Pasted(source, moved, true));
                } else {
                    done.add(new Pasted(source, ProjectFiles.copyInto(projectRoot, source, directory), false));
                }
            } catch (NoSuchFileException gone) {
                failures.add(label(source) + ": " + (gone.getReason() == null ? "no longer exists." : gone.getReason()));
                // it is gone for good, so a cut must not offer it again and again
                if (mode == Mode.COPY) keep.add(source);
            } catch (IOException ex) {
                failures.add(label(source) + ": " + ex.getMessage());
                keep.add(source);
            }
        }
        if (mode == Mode.CUT) sources = List.copyOf(keep);
        return new Result(List.copyOf(done), List.copyOf(failures), unchanged);
    }

    private static String label(Path path) { return String.valueOf(path.getFileName()); }
}
