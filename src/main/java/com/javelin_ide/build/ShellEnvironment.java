package com.javelin_ide.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Resolves the environment a developer expects in a terminal.
 *
 * <p>Apps started from Finder, the Dock or Spotlight inherit launchd's minimal environment (PATH is just
 * {@code /usr/bin:/bin:/usr/sbin:/sbin}), so tools installed through Homebrew, SDKMAN, nvm or a JDK installer
 * (mvn, java, git, node ...) are not found by the terminal, by builds or by language servers. An app started from
 * a terminal inherits the shell's environment and works, which is the difference reported as "it only works when I
 * run javelin from a terminal".
 *
 * <p>This asks the user's login shell for its whole environment once (PATH, JAVA_HOME, SDKMAN_DIR, NVM_DIR ...),
 * caches it, and layers it over the inherited one. If the shell cannot be asked (it fails, hangs or prints
 * nothing usable), the inherited environment is used with the usual Homebrew directories added to PATH, and
 * {@link #source()} says so.
 */
public final class ShellEnvironment {
    /** Where the cached environment came from. */
    public enum Source {
        /** The user's login shell was asked and answered. */
        LOGIN_SHELL,
        /** The login shell could not be used; inherited environment plus well-known tool directories. */
        FALLBACK,
        /** Nothing to resolve on this platform (Windows): the inherited environment as it is. */
        INHERITED
    }

    /** The resolved environment together with where it came from. */
    public record Resolved(Map<String, String> variables, Source source) {}

    /** Runs a command and returns its standard output; throws when it fails or exceeds the timeout. */
    interface Probe {
        String run(List<String> command, long timeoutSeconds) throws Exception;
    }

    static final String BEGIN = "__javelin_ide_env_begin__";
    static final String END = "__javelin_ide_env_end__";
    /** Login shells can take a while when rc files start nvm, SDKMAN or conda. */
    static final long TIMEOUT_SECONDS = 10;
    /** Describes a shell session, not the environment: taking them from the login shell would be wrong. */
    private static final Set<String> NOT_INHERITED_FROM_LOGIN_SHELL =
            Set.of("PWD", "OLDPWD", "SHLVL", "_", "TERM", "COLUMNS", "LINES");
    private static final List<String> WELL_KNOWN_TOOL_DIRS =
            List.of("/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin", "/usr/local/sbin");

    private static volatile Resolved resolved;

    private ShellEnvironment() {}

    /** Resolves the environment on a background thread so the first terminal or build does not wait for it. */
    public static void warmUp() {
        Thread t = new Thread(ShellEnvironment::resolved, "shell-env-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** The environment for processes started by the editor (cached after the first call). */
    public static Map<String, String> environment() {
        return resolved().variables();
    }

    /** Where {@link #environment()} came from; lets the UI tell the user when the login shell was not used. */
    public static Source source() {
        return resolved().source();
    }

    /** The PATH of {@link #environment()}, or an empty string when there is none. */
    public static String path() {
        return environment().getOrDefault("PATH", "");
    }

    /** Applies the resolved environment to a process about to be started. */
    public static ProcessBuilder apply(ProcessBuilder pb) {
        pb.environment().putAll(environment());
        return pb;
    }

    private static Resolved resolved() {
        Resolved r = resolved;
        if (r == null) {
            synchronized (ShellEnvironment.class) {
                r = resolved;
                if (r == null) {
                    r = resolve(
                            System.getenv(),
                            System.getenv("SHELL"),
                            ShellCommand.isWindows(),
                            ShellEnvironment::runProbe,
                            Files::isDirectory);
                    resolved = r;
                }
            }
        }
        return r;
    }

    /** Resolves without touching the cache, from the given inherited environment and shell (for tests of the whole chain). */
    public static Resolved resolveFrom(Map<String, String> inherited, String shell) {
        return resolve(inherited, shell, ShellCommand.isWindows(), ShellEnvironment::runProbe, Files::isDirectory);
    }

    /** Forgets the cached result so the next call resolves again. Meant for tests. */
    static void reset() {
        synchronized (ShellEnvironment.class) {
            resolved = null;
        }
    }

    /**
     * Pure resolution step: no globals are read, so every outcome can be tested.
     *
     * @param inherited the environment this process was started with
     * @param shell the user's shell (usually {@code $SHELL}); blank means /bin/zsh
     * @param windows true on Windows, where there is nothing to resolve
     * @param probe runs the login shell
     * @param isDirectory tells whether a fallback tool directory exists
     */
    static Resolved resolve(
            Map<String, String> inherited,
            String shell,
            boolean windows,
            Probe probe,
            Predicate<Path> isDirectory) {
        if (windows) {
            return new Resolved(Collections.unmodifiableMap(new LinkedHashMap<>(inherited)), Source.INHERITED);
        }
        String sh = shell == null || shell.isBlank() ? "/bin/zsh" : shell;
        try {
            // -l reads the login files (.zprofile, where Homebrew's shellenv usually is), -i the interactive one
            // (.zshrc, where nvm and SDKMAN usually are). The markers fence off whatever the rc files print.
            String script = "printf '%s' " + BEGIN + "; env -0; printf '%s' " + END;
            String out = probe.run(List.of(sh, "-l", "-i", "-c", script), TIMEOUT_SECONDS);
            Map<String, String> login = parse(out);
            if (!login.isEmpty() && login.containsKey("PATH")) {
                return new Resolved(Collections.unmodifiableMap(merge(inherited, login)), Source.LOGIN_SHELL);
            }
        } catch (Exception ignored) {
            // fall through to the fallback below
        }
        return new Resolved(Collections.unmodifiableMap(withToolDirs(inherited, isDirectory)), Source.FALLBACK);
    }

    /** Reads the NUL-separated {@code NAME=value} entries between the markers; empty when the markers are missing. */
    static Map<String, String> parse(String output) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (output == null) return vars;
        int begin = output.lastIndexOf(BEGIN);
        int end = output.lastIndexOf(END);
        if (begin < 0 || end < begin) return vars;
        String body = output.substring(begin + BEGIN.length(), end);
        for (String entry : body.split("\0")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String name = entry.substring(0, eq);
            if (name.contains("\n") || name.contains(" ")) continue;
            vars.put(name, entry.substring(eq + 1));
        }
        return vars;
    }

    /** Login-shell values win, except for session details; inherited variables the shell does not set are kept. */
    static Map<String, String> merge(Map<String, String> inherited, Map<String, String> login) {
        Map<String, String> merged = new LinkedHashMap<>(inherited);
        for (Map.Entry<String, String> e : login.entrySet()) {
            if (NOT_INHERITED_FROM_LOGIN_SHELL.contains(e.getKey())) continue;
            merged.put(e.getKey(), e.getValue());
        }
        merged.put("PATH", joinPaths(login.get("PATH"), inherited.get("PATH")));
        return merged;
    }

    /** The inherited environment with the standard tool directories that exist appended to PATH. */
    static Map<String, String> withToolDirs(Map<String, String> inherited, Predicate<Path> isDirectory) {
        Map<String, String> result = new LinkedHashMap<>(inherited);
        List<String> extra = new ArrayList<>();
        for (String dir : WELL_KNOWN_TOOL_DIRS) {
            if (isDirectory.test(Path.of(dir))) extra.add(dir);
        }
        String base = inherited.get("PATH");
        if (base == null || base.isBlank()) base = "/usr/bin:/bin:/usr/sbin:/sbin";
        result.put("PATH", joinPaths(base, String.join(":", extra)));
        return result;
    }

    /** Joins PATH lists in order, dropping empty entries and repeats. */
    static String joinPaths(String... lists) {
        Set<String> entries = new LinkedHashSet<>();
        for (String list : lists) {
            if (list == null) continue;
            for (String entry : list.split(":")) {
                if (!entry.isBlank()) entries.add(entry);
            }
        }
        return String.join(":", entries);
    }

    private static String runProbe(List<String> command, long timeoutSeconds) throws Exception {
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            p.getOutputStream().close();
            // Read while waiting: a large environment would otherwise fill the pipe and stall the shell.
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return p.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            byte[] bytes = output.get(timeoutSeconds, TimeUnit.SECONDS);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            p.destroyForcibly();
        }
    }
}
