package com.javelin.ui.shell;

import java.io.File;
import javafx.scene.control.Tab;

/**
 * What the editor tells the rest of the window. Features that react to documents (language servers, crash recovery,
 * the project tree, settings) register one; the editor knows none of them, so the dependency only points one way.
 */
public interface EditorListener {
    /** A document was opened, or its text was replaced from disk. */
    default void opened(File file, String text) {}

    /** Text changed in a document that has a file. */
    default void changed(File file, int position, int removedLength, String inserted) {}

    /** A document was written to {@code file}; {@code previous} is where it was saved before (or {@code null}). */
    default void saved(File file, File previous, String text) {}

    /** A document is no longer known under {@code file} (closed, moved or saved under another name). */
    default void closed(File file) {}

    /** The tab's unsaved text is settled: saved, discarded or the tab is gone. Nothing needs to be kept for it. */
    default void settled(Tab tab) {}
}
