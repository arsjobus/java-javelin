package com.javelin_ide.io;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;

/**
 * The operating system's own trash (Finder's Trash, the Windows Recycle Bin) through {@link Desktop#moveToTrash}.
 * Needs a desktop session, so it is the one part of {@link Trash} that cannot run in a headless test; it holds no
 * logic beyond asking the JDK, and is left out of the coverage gate (see pom.xml) for that reason.
 */
final class DesktopTrash {
    private DesktopTrash() {}

    /** The system bin, or {@code null} when this JVM has no desktop integration or its desktop cannot trash files. */
    static Trash.Bin bin() {
        try {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return null;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) return null;
            return path -> {
                if (!desktop.moveToTrash(path.toFile())) throw new IOException("the operating system refused");
            };
        } catch (RuntimeException | LinkageError ex) { // no AWT, or no display
            return null;
        }
    }
}
