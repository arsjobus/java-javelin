package com.javelin.build;

import java.util.List;

/** Wraps a command line so the platform shell resolves PATH, quoting, and pipes. */
public final class ShellCommand {
    private ShellCommand() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** A one-shot command: the shell runs the line and exits. */
    public static List<String> wrap(String commandLine) {
        return isWindows() ? List.of("cmd.exe", "/c", commandLine) : List.of(shell(), "-c", commandLine);
    }

    /** An interactive shell that reads commands from stdin. No PTY, so no job control or full-screen programs. */
    public static List<String> interactive() {
        return isWindows() ? List.of("cmd.exe") : List.of(shell());
    }

    private static String shell() {
        String s = System.getenv("SHELL");
        return s == null || s.isBlank() ? "/bin/sh" : s;
    }
}
