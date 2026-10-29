package com.javelin_ide.launch;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads the command line the app was started with, for example {@code open -a "Javelin IDE" --args /path/to/project},
 * which is what the {@code Javelin} terminal command runs. The first plain argument is the project directory.
 * JavaFX-free so it can be unit tested.
 */
public final class LaunchArguments {
    private LaunchArguments() {}

    /**
     * What the arguments asked for: the directory to open as a project (if any) and a message for the status bar
     * when an argument was given but could not be used.
     */
    public record Result(Optional<Path> directory, Optional<String> problem) {
        static Result nothing() { return new Result(Optional.empty(), Optional.empty()); }
    }

    /**
     * Picks the project directory out of {@code args}. Options (anything starting with {@code -}, which includes the
     * {@code -psn_...} argument older macOS versions add when an app is started from Finder) are ignored, and so is
     * every argument after the first plain one. A relative path is taken relative to {@code workingDirectory}.
     */
    public static Result parse(List<String> args, Path workingDirectory) {
        for (String arg : args) {
            if (arg == null || arg.isBlank() || arg.startsWith("-")) continue;
            try {
                Path directory = workingDirectory.resolve(arg).toAbsolutePath().normalize();
                if (Files.isDirectory(directory)) return new Result(Optional.of(directory), Optional.empty());
                return new Result(Optional.empty(), Optional.of("Not a directory: " + arg));
            } catch (InvalidPathException e) {
                return new Result(Optional.empty(), Optional.of("Not a valid path: " + arg));
            }
        }
        return Result.nothing();
    }
}
