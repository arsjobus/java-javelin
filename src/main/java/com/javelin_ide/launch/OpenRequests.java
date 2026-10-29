package com.javelin_ide.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * How the {@code Javelin} terminal command hands a folder to an editor window that is already running.
 *
 * <p>macOS does not pass new command-line arguments to a running app, and Javelin IDE never opens a network socket
 * (see {@code OfflineGuardTest}), so the command drops a small file into a folder under the config directory and the
 * running app picks it up within a second. The file is named {@code <something>.request} and holds one line: the
 * absolute path of the directory to open. It is written under a temporary name and renamed, so the app never sees a
 * half-written request. {@code packaging/macos/Javelin} writes the same format from the shell.
 */
public final class OpenRequests {
    /** Name of the folder, inside the config directory, that holds pending requests. */
    public static final String DIRECTORY_NAME = "open-requests";
    /** Ending of a complete request file; anything else in the folder is ignored. */
    public static final String SUFFIX = ".request";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final Path directory;

    public OpenRequests(Path directory) { this.directory = directory; }

    public Path directory() { return directory; }

    /** Writes a request to open {@code project}, creating the folder if needed, and returns the request file. */
    public Path post(Path project) throws IOException {
        Files.createDirectories(directory);
        String name = String.format("%013d-%06d", System.currentTimeMillis(), SEQUENCE.incrementAndGet());
        Path temporary = directory.resolve("." + name + ".tmp");
        Files.writeString(temporary, project.toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8);
        Path request = directory.resolve(name + SUFFIX);
        Files.move(temporary, request, StandardCopyOption.ATOMIC_MOVE);
        return request;
    }

    /**
     * Consumes every pending request and returns the directory of the newest valid one. A request is valid when its
     * first line is an absolute path to a directory that exists; invalid and unreadable requests are discarded
     * rather than left to be retried forever.
     */
    public Optional<Path> take() {
        List<Path> requests;
        try (Stream<Path> files = Files.list(directory)) {
            requests = files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        } catch (IOException e) {
            return Optional.empty(); // the folder does not exist yet: nothing has been requested
        }
        Path newest = null;
        for (Path request : requests) {
            try {
                String line = Files.readString(request, StandardCharsets.UTF_8).lines().findFirst().orElse("").strip();
                Path candidate = Path.of(line);
                if (!line.isEmpty() && candidate.isAbsolute() && Files.isDirectory(candidate)) newest = candidate;
            } catch (IOException | InvalidPathException e) {
                // unreadable or not a path: dropped below
            }
            try { Files.deleteIfExists(request); } catch (IOException e) { /* cannot be removed; ignored, it stays invalid */ }
        }
        return Optional.ofNullable(newest);
    }
}
