package com.javelin.terminal;

import com.javelin.build.ManagedProcess;
import com.javelin.build.ProcessManager;
import com.javelin.build.ShellCommand;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One persistent shell behind a terminal tab, with no JavaFX dependency.
 *
 * <ul>
 *   <li><b>Foreground binding:</b> while the shell has a child process (e.g. a server), the session is
 *       "busy". Typed lines go to the shell's stdin, which the child inherits, and {@link #interrupt()}
 *       sends it Ctrl+C (SIGINT) without killing the shell.</li>
 *   <li><b>Background jobs:</b> a line ending in a lone {@code &} (e.g. {@code npm start &}) is launched in the
 *       background. Its processes are remembered and no longer count as the foreground command, so the
 *       session stays idle and Ctrl+C leaves them running. A lone {@code &} is recognized anywhere on the line
 *       (so {@code a & b} backgrounds {@code a} and runs {@code b} in the foreground), but not inside quotes,
 *       after a backslash, in a comment, or as part of {@code &&}, {@code &>}, {@code >&}, {@code <&} or
 *       {@code |&}; and not on Windows, where {@code &} is just a command separator.</li>
 *   <li><b>Virtualenv awareness:</b> a PTY-less shell cannot show a prompt, so once the shell has been idle
 *       after a command the session sends one read-only probe (a single printf of $VIRTUAL_ENV) and hides
 *       its output. The probe is only sent while idle, so it can never be consumed by a running program.</li>
 * </ul>
 *
 * Listener callbacks may arrive on any thread. {@link #tick()} should be called a few times per second.
 */
public final class TerminalSession {
    public interface Listener {
        void onOutput(String line);
        void onStateChanged();
    }

    static final String MARKER = "__javelin_ide_env__";
    private static final String PROBE = ShellCommand.isWindows() ? "echo " + MARKER + "%VIRTUAL_ENV%" : "printf '" + MARKER + "%s\\n' \"$VIRTUAL_ENV\"";
    private static final long IDLE_AFTER_MILLIS = 600;
    /** How long after launching a background job we keep looking for its processes before giving up. */
    private static final long BACKGROUND_WINDOW_MILLIS = 3000;

    private final String name;
    private final ProcessManager processes;
    private final Supplier<File> directory;
    private final Listener listener;
    private ManagedProcess shell;
    private boolean busy;
    private boolean probePending;
    private long lastSubmit;
    private String lastCommand = "";
    private String venvPath;
    private final Set<Long> backgroundPids = new HashSet<>();
    private Set<Long> backgroundBaseline;
    private long backgroundSince;
    private int backgroundExpected;     // lone `&` operators on the line being classified
    private int backgroundClaimed;      // processes claimed so far for that line
    private boolean backgroundTail;     // true when a foreground command follows the last `&`

    public TerminalSession(String name, ProcessManager processes, Supplier<File> directory, Listener listener) {
        this.name = name; this.processes = processes; this.directory = directory; this.listener = listener;
    }

    /** Sends a line to the shell, starting it first if needed. While a command runs, the line becomes that command's input. */
    public synchronized void submit(String line) throws IOException {
        ensureShell();
        classifyBackground(); // settle any earlier `&` launch before this line can start a foreground child
        if (!busy) {
            lastCommand = line.strip();
            int jobs = backgroundOperators(line);
            if (jobs > 0) {
                backgroundBaseline = childPids(); backgroundSince = System.currentTimeMillis();
                backgroundExpected = jobs; backgroundClaimed = 0; backgroundTail = hasForegroundTail(line);
            }
        }
        lastSubmit = System.currentTimeMillis();
        probePending = true;
        shell.send(line);
    }

    /** Ctrl+C: interrupts the running command, if any. Returns false when nothing was running. */
    public synchronized boolean interrupt() {
        if (shell == null || !shell.isAlive()) return false;
        List<ProcessHandle> foreground = foregroundChildren();
        return !foreground.isEmpty() && processes.interrupt(foreground) > 0;
    }

    /** Ctrl+D on an empty prompt: exits the shell, but only when no command is running. */
    public synchronized boolean exitIfIdle() throws IOException {
        if (shell == null || !shell.isAlive() || busy) return false;
        shell.send("exit");
        return true;
    }

    /** Refreshes busy state and, once idle after a command, probes for an active virtualenv. */
    public synchronized void tick() {
        classifyBackground();
        boolean nowBusy = shell != null && shell.isAlive() && !foregroundChildren().isEmpty();
        if (nowBusy != busy) { busy = nowBusy; listener.onStateChanged(); }
        if (!busy && probePending && shell != null && shell.isAlive() && System.currentTimeMillis() - lastSubmit >= IDLE_AFTER_MILLIS) {
            probePending = false;
            try { shell.send(PROBE); } catch (IOException ignored) { /* shell is going away */ }
        }
    }

    public synchronized boolean isBusy() { return busy; }

    /** Number of background jobs (started with a trailing {@code &}) that are still running. */
    public synchronized int backgroundJobs() {
        if (shell == null || !shell.isAlive()) return 0;
        backgroundPids.retainAll(childPids());
        return backgroundPids.size();
    }

    /** The command line the shell is currently running, or empty when idle. */
    public synchronized String foreground() { return busy ? lastCommand : ""; }

    /** Name of the active virtualenv directory (e.g. ".venv"), if one is active in this shell. */
    public synchronized Optional<String> venvName() {
        if (venvPath == null) return Optional.empty();
        String leaf = new File(venvPath).getName();
        return Optional.of(leaf.isBlank() ? venvPath : leaf);
    }

    public synchronized void close() {
        if (shell != null && shell.isAlive()) processes.stop(shell);
    }

    /** True when the line contains at least one lone {@code &} that backgrounds a command (see {@link #backgroundOperators}). */
    static boolean launchesBackgroundJob(String line) { return backgroundOperators(line) > 0; }

    /** True when a command follows the last backgrounding {@code &}, e.g. the {@code b} in {@code a & b}. */
    static boolean hasForegroundTail(String line) { return scan(line)[1] == 1; }

    /**
     * Number of lone {@code &} operators that background a command. Skips quoted or escaped ampersands, comments,
     * {@code &&}, {@code &>}, {@code >&}, {@code <&} and {@code |&}, and an {@code &} with no command before it.
     */
    static int backgroundOperators(String line) { return scan(line)[0]; }

    /** Returns {count of backgrounding operators, 1 if a command follows the last one}. */
    private static int[] scan(String line) {
        if (ShellCommand.isWindows() || line == null) return new int[] {0, 0};
        int count = 0;
        boolean hasCommand = false, single = false, dbl = false, wordStart = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (single) { if (c == '\'') single = false; continue; }
            if (dbl) {
                if (c == '\\') i++;
                else if (c == '"') dbl = false;
                continue;
            }
            if (c == '\\') { hasCommand = true; wordStart = false; i++; continue; }
            if (c == '\'') { single = true; hasCommand = true; wordStart = false; continue; }
            if (c == '"') { dbl = true; hasCommand = true; wordStart = false; continue; }
            if (c == '#' && wordStart) break;
            if (c == '&') {
                char prev = i > 0 ? line.charAt(i - 1) : ' ';
                char next = i + 1 < line.length() ? line.charAt(i + 1) : ' ';
                if (next == '&') { hasCommand = false; i++; wordStart = true; continue; }   // &&
                if (prev == '>' || prev == '<' || prev == '|' || next == '>') { wordStart = false; continue; } // redirections, |&
                if (hasCommand) count++;
                hasCommand = false; wordStart = true;
                continue;
            }
            if (c == ';' || c == '|' || c == '(' || c == '\n') { hasCommand = false; wordStart = true; continue; }
            if (Character.isWhitespace(c)) { wordStart = true; continue; }
            hasCommand = true; wordStart = false;
        }
        return new int[] {count, count > 0 && hasCommand ? 1 : 0};
    }

    private Set<Long> childPids() {
        Set<Long> pids = new HashSet<>();
        if (shell != null && shell.isAlive()) for (ProcessHandle child : shell.children()) pids.add(child.pid());
        return pids;
    }

    /** Children of the shell that are not background jobs, i.e. what the shell is actually waiting for. */
    private List<ProcessHandle> foregroundChildren() {
        backgroundPids.retainAll(childPids());
        return shell.children().stream().filter(c -> !backgroundPids.contains(c.pid())).toList();
    }

    /**
     * After a `&` launch, children that were not there before the line was sent are the background job(s). When a
     * foreground command follows the `&` (`a & b`), only the earliest-started new children, one per `&`, are claimed
     * so that `b` stays the foreground command.
     */
    private void classifyBackground() {
        if (backgroundBaseline == null) return;
        if (shell == null || !shell.isAlive()) { backgroundBaseline = null; return; }
        List<Long> fresh = new ArrayList<>();
        for (long pid : childPids()) if (!backgroundBaseline.contains(pid) && !backgroundPids.contains(pid)) fresh.add(pid);
        fresh.sort(Comparator.comparing(TerminalSession::startedAt).thenComparing(Comparator.naturalOrder()));
        if (backgroundTail) {
            int room = backgroundExpected - backgroundClaimed;
            if (fresh.size() > room) fresh = new ArrayList<>(fresh.subList(0, Math.max(room, 0)));
        }
        backgroundPids.addAll(fresh);
        backgroundClaimed += fresh.size();
        boolean done = !fresh.isEmpty() && (!backgroundTail || backgroundClaimed >= backgroundExpected);
        // A job that already finished (`echo hi &`) never shows up, so stop looking after a while.
        if (done || System.currentTimeMillis() - backgroundSince > BACKGROUND_WINDOW_MILLIS) backgroundBaseline = null;
    }

    private static Instant startedAt(long pid) {
        return ProcessHandle.of(pid).flatMap(h -> h.info().startInstant()).orElse(Instant.MAX);
    }

    private void ensureShell() throws IOException {
        if (shell != null && shell.isAlive()) return;
        File dir = directory.get();
        if (dir == null) dir = new File(System.getProperty("user.home"));
        listener.onOutput("[starting shell in " + dir + "]");
        busy = false; probePending = false; venvPath = null;
        backgroundPids.clear(); backgroundBaseline = null;
        // Without a PTY, Python block-buffers stdout when piped, so servers would look silent. Unbuffered output fixes that.
        shell = processes.start(name, ShellCommand.interactive(), dir, Map.of("PYTHONUNBUFFERED", "1"), new ProcessManager.Listener() {
            @Override public void onLine(ManagedProcess p, String line) { handleLine(line); }
            @Override public void onExit(ManagedProcess p, int code) { handleExit(p, code); }
        });
        listener.onStateChanged();
    }

    private synchronized void handleLine(String line) {
        int at = line.indexOf(MARKER);
        if (at < 0) { listener.onOutput(line); return; }
        String value = line.substring(at + MARKER.length()).strip();
        String venv = value.isEmpty() || value.contains("%VIRTUAL_ENV%") ? null : value;
        if (!Objects.equals(venv, venvPath)) { venvPath = venv; listener.onStateChanged(); }
    }

    private synchronized void handleExit(ManagedProcess p, int code) {
        if (p != shell) return;
        busy = false; probePending = false; venvPath = null;
        backgroundPids.clear(); backgroundBaseline = null;
        listener.onOutput("[shell exited with code " + code + "]");
        listener.onStateChanged();
    }
}
