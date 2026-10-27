package com.javelin_ide.io;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Creating, renaming, moving and deleting files and folders in the project tree. JavaFX-free, so the rules are tested
 * without a display.
 *
 * <p>Creating, renaming and moving never overwrite anything. Deleting is the dangerous half, so it is strict: it refuses the project
 * folder itself, anything outside the project (including paths that escape through a symbolic link), and it never
 * follows a symbolic link, so deleting a link removes the link and leaves what it points at alone.
 */
public final class ProjectFiles {
    /** The longest file or folder name most file systems accept. */
    public static final int MAX_NAME_LENGTH = 255;

    private ProjectFiles() {}

    /** How many files and folders are inside something that is about to be deleted. */
    public record Contents(int files, int folders) {
        /** Words for a confirmation dialog, e.g. {@code 3 files and 2 folders}; empty when there is nothing inside. */
        public String describe() {
            String f = files == 0 ? "" : files + (files == 1 ? " file" : " files");
            String d = folders == 0 ? "" : folders + (folders == 1 ? " folder" : " folders");
            return f.isEmpty() ? d : d.isEmpty() ? f : f + " and " + d;
        }
    }

    /** What is wrong with a proposed name, in words a person can act on, or {@code null} if it is fine. */
    public static String nameProblem(String name) {
        if (name == null || name.isBlank()) return "Enter a name.";
        String n = name.strip();
        if (n.equals(".") || n.equals("..")) return "\"" + n + "\" is not a valid name.";
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) return "A name cannot contain / or \\. Create the folders one at a time.";
        for (int i = 0; i < n.length(); i++) if (Character.isISOControl(n.charAt(i))) return "A name cannot contain control characters.";
        if (n.length() > MAX_NAME_LENGTH) return "A name can be at most " + MAX_NAME_LENGTH + " characters long.";
        return null;
    }

    /** Where something new belongs when {@code selected} is right-clicked: inside a folder, next to a file. */
    public static Path directoryFor(Path selected) {
        if (Files.isDirectory(selected)) return selected;
        Path parent = selected.toAbsolutePath().getParent();
        return parent == null ? selected.toAbsolutePath() : parent;
    }

    /** Creates an empty file called {@code name} in {@code directory}. Never overwrites an existing file or folder. */
    public static Path createFile(Path directory, String name) throws IOException {
        Path target = newTarget(directory, name);
        try {
            return Files.createFile(target);
        } catch (FileAlreadyExistsException ex) { // atomic: also covers a file, a folder or a dangling link already there
            throw exists(directory, target);
        }
    }

    /** Creates an empty folder called {@code name} in {@code directory}. Never overwrites an existing file or folder. */
    public static Path createFolder(Path directory, String name) throws IOException {
        Path target = newTarget(directory, name);
        try {
            return Files.createDirectory(target);
        } catch (FileAlreadyExistsException ex) {
            throw exists(directory, target);
        }
    }

    private static Path newTarget(Path directory, String name) throws IOException {
        String problem = nameProblem(name);
        if (problem != null) throw new IOException(problem);
        if (!Files.isDirectory(directory)) throw new IOException(directory.getFileName() + " is not a folder, so nothing can be created in it.");
        return directory.resolve(name.strip());
    }

    private static IOException exists(Path directory, Path target) {
        return new IOException("\"" + target.getFileName() + "\" already exists in " + directory.getFileName() + ".");
    }

    /** Counts what is inside {@code target} (a plain file counts as one file); links are counted, not followed. */
    public static Contents contents(Path target) throws IOException {
        int[] count = new int[2];
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) { count[0]++; return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { if (!dir.equals(target)) count[1]++; return FileVisitResult.CONTINUE; }
        });
        return new Contents(count[0], count[1]);
    }

    /** True if {@code candidate} is {@code ancestor} or lies somewhere below it. Compares paths; touches no files. */
    public static boolean isInside(Path ancestor, Path candidate) {
        return candidate.toAbsolutePath().normalize().startsWith(ancestor.toAbsolutePath().normalize());
    }

    /** Returns selected paths in parent-first order, omitting any item already covered by a selected folder. */
    public static List<Path> topLevelTargets(Collection<Path> selected) {
        List<Path> ordered = selected.stream().map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparingInt(Path::getNameCount)).toList();
        List<Path> result = new ArrayList<>();
        for (Path path : ordered) if (result.stream().noneMatch(path::startsWith)) result.add(path);
        return List.copyOf(result);
    }

    /**
     * Deletes a file, or a folder with everything in it. Refuses the project folder itself and anything that is not
     * inside {@code projectRoot}. Symbolic links are removed, never followed.
     */
    public static void delete(Path projectRoot, Path target) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path victim = target.toAbsolutePath().normalize();
        if (victim.equals(root)) throw new IOException("The project folder itself cannot be deleted from here.");
        if (!victim.startsWith(root)) throw new IOException(victim.getFileName() + " is not inside the project, so it will not be deleted.");
        if (!Files.exists(victim, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(victim.toString(), null, victim.getFileName() + " no longer exists.");
        // A symbolic link in the middle of the path could lead out of the project; check where the parent really is.
        if (!victim.getParent().toRealPath().startsWith(root.toRealPath())) throw new IOException(victim.getFileName() + " is not inside the project, so it will not be deleted.");
        Files.walkFileTree(victim, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException ex) throws IOException { // if listing failed, the delete below fails loudly too
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Gives {@code source} a new name, keeping it in the same folder. Never overwrites; see {@link #move}. */
    public static Path rename(Path projectRoot, Path source, String newName) throws IOException {
        String problem = nameProblem(newName);
        if (problem != null) throw new IOException(problem);
        Path from = source.toAbsolutePath().normalize();
        Path parent = from.getParent();
        if (parent == null) throw new IOException("The project folder itself cannot be renamed from here.");
        return move(projectRoot, from, parent, newName.strip());
    }

    /** Moves {@code source} into {@code directory}, keeping its name. Never overwrites; see {@link #move}. */
    public static Path moveInto(Path projectRoot, Path source, Path directory) throws IOException {
        Path from = source.toAbsolutePath().normalize();
        Path name = from.getFileName();
        if (name == null) throw new IOException("The project folder itself cannot be moved from here.");
        return move(projectRoot, from, directory, name.toString());
    }

    /**
     * Moves {@code source} to {@code directory/name}. Refuses the project folder itself, anything outside
     * {@code projectRoot} (as the source or as the destination, including paths that escape through a symbolic link),
     * and a folder moved into itself. It never overwrites: if something is already there it fails, except that a
     * change of letter case alone is allowed on a case-insensitive file system. Symbolic links are moved, never
     * followed. Moving something to where it already is does nothing and returns that path.
     */
    public static Path move(Path projectRoot, Path source, Path directory, String name) throws IOException {
        String problem = nameProblem(name);
        if (problem != null) throw new IOException(problem);
        Path root = projectRoot.toAbsolutePath().normalize();
        Path from = source.toAbsolutePath().normalize();
        Path toDir = directory.toAbsolutePath().normalize();
        if (from.equals(root)) throw new IOException("The project folder itself cannot be moved or renamed from here.");
        if (!from.startsWith(root)) throw new IOException(from.getFileName() + " is not inside the project, so it will not be moved.");
        if (!toDir.startsWith(root)) throw new IOException("The destination is not inside the project, so nothing will be moved there.");
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(from.toString(), null, from.getFileName() + " no longer exists.");
        if (!Files.isDirectory(toDir)) throw new IOException(toDir.getFileName() + " is not a folder, so nothing can be moved into it.");
        // Symbolic links in the middle of either path could lead out of the project; check where both really are.
        Path realRoot = root.toRealPath();
        if (!from.getParent().toRealPath().startsWith(realRoot)) throw new IOException(from.getFileName() + " is not inside the project, so it will not be moved.");
        if (!toDir.toRealPath().startsWith(realRoot)) throw new IOException("The destination is not inside the project, so nothing will be moved there.");
        if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS) && toDir.startsWith(from))
            throw new IOException("A folder cannot be moved into itself.");
        Path target = toDir.resolve(name.strip());
        if (target.equals(from)) return from; // nothing to do
        boolean caseOnly = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && onlyCaseDiffers(from, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !caseOnly)
            throw new IOException("\"" + target.getFileName() + "\" already exists in " + toDir.getFileName() + ".");
        if (caseOnly) { // Files.move silently does nothing when source and target are the same file, so go through a temporary name
            Path temp = toDir.resolve(".rename-" + System.nanoTime() + ".tmp");
            Files.move(from, temp);
            try { return Files.move(temp, target); } catch (IOException ex) { Files.move(temp, from); throw ex; } // put it back if the second step fails
        }
        return Files.move(from, target); // no REPLACE_EXISTING: if something appears meanwhile this fails rather than overwrites
    }

    /** True when {@code a} and {@code b} are the same existing entry whose names differ only in letter case (case-insensitive file system). */
    static boolean onlyCaseDiffers(Path a, Path b) throws IOException {
        return a.getParent().equals(b.getParent())
            && !a.getFileName().toString().equals(b.getFileName().toString())
            && a.getFileName().toString().equalsIgnoreCase(b.getFileName().toString())
            && Files.isSameFile(a, b);
    }

    /**
     * Where {@code file} ends up after {@code oldBase} was moved to {@code newBase}: the same path below the new
     * location, {@code newBase} itself if {@code file} is {@code oldBase}, or {@code null} if {@code file} was not
     * inside {@code oldBase}. Compares paths only; used to repoint open editor tabs after a rename or move.
     */
    public static Path relocated(Path oldBase, Path newBase, Path file) {
        Path old = oldBase.toAbsolutePath().normalize();
        Path f = file.toAbsolutePath().normalize();
        if (!f.startsWith(old)) return null;
        return newBase.toAbsolutePath().normalize().resolve(old.relativize(f).toString()).normalize();
    }
}
