package com.javelin_ide.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;

/** Small, local shell-style completion for the line-oriented terminal (which has no PTY). */
public final class TerminalCompletion {
    private TerminalCompletion() {}

    /**
     * Completes the token immediately before the caret using filesystem entries and, for the first token,
     * executable names from PATH. Multiple matches extend to their common prefix. The unedited suffix is kept.
     */
    public static String complete(String line, int caret, Path directory, Map<String, String> environment, boolean windows) {
        if (line == null || directory == null || environment == null) return line;
        int end = Math.max(0, Math.min(caret, line.length()));
        int start = end;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) start--;
        String token = line.substring(start, end);
        if (token.isEmpty()) return line;

        boolean commandPosition = line.substring(0, start).isBlank();
        Comparator<String> comparator = windows ? String.CASE_INSENSITIVE_ORDER : Comparator.naturalOrder();
        TreeSet<String> matches = new TreeSet<>(comparator);
        collectPathMatches(matches, token, directory, windows, !commandPosition);
        if (commandPosition && token.indexOf('/') < 0 && token.indexOf('\\') < 0) {
            collectExecutables(matches, token, environment, windows);
        }
        if (matches.isEmpty()) return line;

        String replacement = token;
        if (matches.size() == 1) {
            String match = matches.first();
            replacement = match;
            Path resolved = resolvePath(directory, match);
            if (resolved != null && Files.isDirectory(resolved)) {
                if (!replacement.endsWith("/") && !replacement.endsWith("\\")) replacement += windows ? "\\" : "/";
            } else {
                replacement += " ";
            }
        } else {
            replacement = commonPrefix(matches, windows);
        }
        if (replacement.length() <= token.length() && matches.size() > 1) return line;
        return line.substring(0, start) + replacement + line.substring(end);
    }

    private static void collectPathMatches(TreeSet<String> matches, String token, Path directory, boolean windows, boolean argumentPosition) {
        int separator = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        String parentText = separator < 0 ? "" : token.substring(0, separator + 1);
        String leaf = token.substring(separator + 1);
        Path parent = resolvePath(directory, parentText.isEmpty() ? "." : parentText);
        if (parent == null || !Files.isDirectory(parent)) return;
        try (var entries = Files.list(parent)) {
            entries.forEach(path -> {
                String name = path.getFileName().toString();
                if (startsWith(name, leaf, windows)) {
                    String candidate = parentText + name;
                    if (argumentPosition || commandExecutable(path, windows) || Files.isDirectory(path) || separator >= 0 || !parentText.isEmpty()) {
                        matches.add(candidate);
                    } else if (name.startsWith(leaf)) {
                        matches.add(candidate);
                    }
                }
            });
        } catch (IOException ignored) {
            // An unreadable directory simply has no completions.
        }
    }

    private static void collectExecutables(TreeSet<String> matches, String token, Map<String, String> environment, boolean windows) {
        String path = environment.getOrDefault(windows ? "Path" : "PATH", environment.get("PATH"));
        if (path == null || path.isBlank()) return;
        for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path folder = Path.of(entry);
            if (!folder.isAbsolute()) folder = folder.toAbsolutePath();
            if (!Files.isDirectory(folder)) continue;
            try (var files = Files.list(folder)) {
                files.filter(file -> commandExecutable(file, windows))
                        .map(file -> file.getFileName().toString())
                        .filter(name -> startsWith(name, token, windows))
                        .forEach(matches::add);
            } catch (IOException ignored) {
                // PATH entries can be missing or unreadable.
            }
        }
    }

    private static boolean commandExecutable(Path path, boolean windows) {
        if (Files.isDirectory(path)) return false;
        if (windows) {
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(".exe") || name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".com");
        }
        return Files.isExecutable(path);
    }

    private static Path resolvePath(Path directory, String text) {
        try {
            Path path = Path.of(text);
            return (path.isAbsolute() ? path : directory.resolve(path)).normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean startsWith(String value, String prefix, boolean windows) {
        return windows ? value.regionMatches(true, 0, prefix, 0, prefix.length()) : value.startsWith(prefix);
    }

    private static String commonPrefix(Iterable<String> values, boolean windows) {
        String prefix = null;
        for (String value : values) {
            if (prefix == null) prefix = value;
            else {
                int i = 0;
                while (i < prefix.length() && i < value.length()
                        && (windows ? Character.toLowerCase(prefix.charAt(i)) == Character.toLowerCase(value.charAt(i))
                                    : prefix.charAt(i) == value.charAt(i))) i++;
                prefix = prefix.substring(0, i);
            }
        }
        return prefix == null ? "" : prefix;
    }
}
