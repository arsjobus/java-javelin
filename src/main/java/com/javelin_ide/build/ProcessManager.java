package com.javelin_ide.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Starts, tracks, and stops child processes. Nothing is started unless a caller asks for it,
 * and no process is left behind: {@link #stopAll()} is meant to be called on application exit.
 * Listener callbacks arrive on background threads; UI code must hop to the FX thread itself.
 */
public final class ProcessManager {
    public interface Listener {
        void onLine(ManagedProcess process, String line);
        void onExit(ManagedProcess process, int exitCode);
    }

    /** Starts processes; injectable so tests can simulate failures a real process rarely produces. */
    interface Launcher { Process start(ProcessBuilder builder) throws IOException; }

    private static final long GRACE_MILLIS = 3000;
    private final Launcher launcher;
    private final Supplier<Map<String, String>> baseEnvironment;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, ManagedProcess> running = new ConcurrentHashMap<>();

    /** Processes get the environment a terminal would give them (see {@link ShellEnvironment}), not launchd's minimal one. */
    public ProcessManager() { this(ProcessBuilder::start, ShellEnvironment::environment); }

    ProcessManager(Launcher launcher) { this(launcher, Map::of); }

    /** @param baseEnvironment variables layered over the editor's own environment for every process, before the per-call ones */
    public ProcessManager(Supplier<Map<String, String>> baseEnvironment) { this(ProcessBuilder::start, baseEnvironment); }

    ProcessManager(Launcher launcher, Supplier<Map<String, String>> baseEnvironment) {
        this.launcher = launcher; this.baseEnvironment = baseEnvironment;
    }

    public ManagedProcess start(String name, List<String> command, File directory, Listener listener) throws IOException {
        return start(name, command, directory, Map.of(), listener);
    }

    /** As above, with extra environment variables layered over the base environment. */
    public ManagedProcess start(String name, List<String> command, File directory, Map<String, String> environment, Listener listener) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(baseEnvironment.get());
        builder.environment().putAll(environment);
        if (directory != null) builder.directory(directory);
        Process process = launcher.start(builder);
        ManagedProcess managed = new ManagedProcess(ids.incrementAndGet(), name, command, directory, process);
        running.put(managed.id(), managed);
        Thread reader = new Thread(() -> pump(managed, listener), "process-" + managed.id());
        reader.setDaemon(true);
        reader.start();
        return managed;
    }

    private void pump(ManagedProcess managed, Listener listener) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(managed.process().getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                // A misbehaving listener must not stop the pump: the process would never be reaped, never reported
                // as exited, and would stay in running() forever (and block on a full pipe).
                try { listener.onLine(managed, Ansi.strip(line)); } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) {
            // The stream closes when the process is destroyed.
        }
        int code;
        try { code = managed.process().waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); code = -1; }
        running.remove(managed.id());
        try { listener.onExit(managed, code); } catch (RuntimeException ignored) { }
    }

    public List<ManagedProcess> running() {
        List<ManagedProcess> list = new ArrayList<>(running.values());
        list.sort((a, b) -> Long.compare(a.id(), b.id()));
        return list;
    }

    /** Asks the process and its children to exit, then force-kills whatever is left after a short grace period. */
    public void stop(ManagedProcess managed) {
        Process process = managed.process();
        if (!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "process-killer-" + managed.id());
        killer.setDaemon(true);
        killer.start();
    }

    public void stopAll() { running().forEach(this::stop); }

    /**
     * Sends the equivalent of Ctrl+C to whatever the given shell is running: SIGINT to its direct children,
     * leaving the shell itself alive. Windows has no equivalent signal, so children are terminated instead.
     * Returns how many children were signalled.
     */
    public int interrupt(ManagedProcess shell) {
        return interrupt(shell.children());
    }

    /** As above, but only signals the given processes (e.g. a shell's foreground children, sparing its background jobs). */
    public int interrupt(List<ProcessHandle> targets) {
        int signalled = 0;
        for (ProcessHandle child : targets) {
            if (ShellCommand.isWindows()) { if (child.destroy()) signalled++; continue; }
            try {
                Process kill = launcher.start(new ProcessBuilder("kill", "-INT", Long.toString(child.pid()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
                if (kill.waitFor(2, TimeUnit.SECONDS) && kill.exitValue() == 0) signalled++;
            } catch (IOException ignored) {
                // No kill binary available; nothing more we can do.
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return signalled;
    }
}
