package com.javelin_ide.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The recycle bin: where files and folders go when the user deletes them. Javelin never removes anything for good;
 * {@link ProjectFiles#trash} hands the entry to a {@link Bin} and, if the bin cannot take it, fails and leaves the
 * entry exactly where it was.
 *
 * <p>{@link #system()} picks the best bin for this computer: the operating system's own (Finder's Trash, the Windows
 * Recycle Bin) through {@link Desktop#moveToTrash}, and when that is not available a plain-folder bin that follows the
 * same layout the desktop uses ({@link #freedesktop} on Linux and other Unix systems, {@link #userTrashFolder} on macOS),
 * so the file can still be restored from the file manager. JavaFX-free so each piece can be tested on its own.
 */
public final class Trash {
    /** Something that can take a file or folder out of the project without destroying it. */
    @FunctionalInterface
    public interface Bin {
        /** Moves {@code path} (a file, a folder with everything in it, or a symbolic link, never followed) into the bin. */
        void moveToTrash(Path path) throws IOException;
    }

    private static final DateTimeFormatter DELETION_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Trash() {}

    /** What the bin is called on this system, for menus and messages: "Recycle Bin" on Windows, "Trash" elsewhere. */
    public static String name() { return name(System.getProperty("os.name", "")); }

    static String name(String osName) { return osName.toLowerCase(Locale.ROOT).contains("windows") ? "Recycle Bin" : "Trash"; }

    /**
     * The bin for this computer. The operating system's own is tried first ({@link DesktopTrash}); if it is missing or
     * refuses, the folder-based fallback for this system is tried; if there is none, the entry is left alone and the
     * error says so.
     */
    public static Bin system() {
        String os = System.getProperty("os.name", "");
        Path home = Path.of(System.getProperty("user.home", "."));
        return combine(os, DesktopTrash.bin(), fallbackFor(os, home, System.getenv("XDG_DATA_HOME")));
    }

    /** Tries {@code desktop} (may be {@code null}), then {@code fallback} (may be {@code null}); fails without touching the entry if neither works. */
    static Bin combine(String osName, Bin desktop, Bin fallback) {
        return path -> {
            String problem = null;
            if (desktop != null) {
                try {
                    desktop.moveToTrash(path);
                    return;
                } catch (IOException | RuntimeException ex) { // the OS said no
                    problem = String.valueOf(ex.getMessage());
                }
            }
            if (fallback == null) {
                throw new IOException("Could not move " + path.getFileName() + " to the " + name(osName) + (problem == null ? ", which is not available here" : " (" + problem + ")")
                    + ". Nothing was deleted.");
            }
            fallback.moveToTrash(path);
        };
    }

    /** The folder-based bin to fall back on for an operating system, or {@code null} if there is none (Windows). */
    static Bin fallbackFor(String osName, Path home, String xdgDataHome) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("windows")) return null;
        if (os.contains("mac") || os.contains("darwin")) return userTrashFolder(home.resolve(".Trash"));
        Path dataHome = xdgDataHome == null || xdgDataHome.isBlank() ? home.resolve(".local").resolve("share") : Path.of(xdgDataHome);
        return freedesktop(dataHome.resolve("Trash"), Clock.systemDefaultZone());
    }

    /**
     * A bin that follows the freedesktop.org Trash specification: the entry goes to {@code trashDir/files} and a
     * {@code .trashinfo} file in {@code trashDir/info} records where it came from and when, which is what file
     * managers need to offer "Restore". Two entries with the same name never overwrite each other.
     */
    public static Bin freedesktop(Path trashDir, Clock clock) {
        return path -> {
            Path source = path.toAbsolutePath().normalize();
            Path files = Files.createDirectories(trashDir.resolve("files"));
            Path infos = Files.createDirectories(trashDir.resolve("info"));
            String base = source.getFileName().toString();
            for (int n = 1; ; n++) {
                String name = n == 1 ? base : base + "." + n;
                Path target = files.resolve(name), info = infos.resolve(name + ".trashinfo");
                try { // creating the info file first reserves the name, so two deletions at once cannot pick the same one
                    Files.writeString(info, "[Trash Info]\nPath=" + encode(source.toString()) + "\nDeletionDate=" + LocalDateTime.now(clock).format(DELETION_DATE) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException taken) { continue; }
                try {
                    Files.move(source, target);
                    return;
                } catch (FileAlreadyExistsException taken) { // an entry without an info file is already there: leave it, try the next name
                    Files.deleteIfExists(info);
                } catch (IOException ex) { // e.g. a folder on another disk: say so and leave it in place, never fall back to deleting
                    Files.deleteIfExists(info);
                    throw new IOException("Could not move " + base + " to the Trash: " + ex.getMessage() + ". Nothing was deleted.", ex);
                }
            }
        };
    }

    /** A bin that is simply a folder (macOS {@code ~/.Trash}): the entry is moved in under a free name, never over another entry. */
    public static Bin userTrashFolder(Path folder) {
        return path -> {
            Path source = path.toAbsolutePath().normalize();
            Files.createDirectories(folder);
            String base = source.getFileName().toString();
            for (int n = 1; ; n++) {
                Path target = folder.resolve(n == 1 ? base : base + " " + n);
                try {
                    Files.move(source, target);
                    return;
                } catch (FileAlreadyExistsException taken) {
                    // something is already there under that name: try the next one
                } catch (IOException ex) {
                    throw new IOException("Could not move " + base + " to the Trash: " + ex.getMessage() + ". Nothing was deleted.", ex);
                }
            }
        };
    }

    /** The absolute path as the Trash specification wants it: UTF-8 bytes, everything but letters, digits, {@code -_.~/} percent-encoded. */
    static String encode(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.replace('\\', '/').getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-_.~/".indexOf(c) >= 0) out.append((char) c);
            else out.append('%').append(String.format("%02X", c));
        }
        return out.toString();
    }
}
