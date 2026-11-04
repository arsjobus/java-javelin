package com.javelin.build;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A child process started on the user's behalf. stderr is merged into stdout. */
public final class ManagedProcess {
  private final long id;
  private final String name;
  private final List<String> command;
  private final File directory;
  private final Instant started = Instant.now();
  private final Process process;

  ManagedProcess(long id, String name, List<String> command, File directory, Process process) {
    this.id = id;
    this.name = name;
    this.command = List.copyOf(command);
    this.directory = directory;
    this.process = process;
  }

  public long id() {
    return id;
  }

  public String name() {
    return name;
  }

  public List<String> command() {
    return command;
  }

  public File directory() {
    return directory;
  }

  public Instant started() {
    return started;
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  Process process() {
    return process;
  }

  /**
   * Direct child processes, e.g. the command a shell is currently running. Finished children that
   * the process has not reaped yet (zombies, such as a completed background job in an idle shell)
   * are left out, because {@link ProcessHandle#isAlive()} still reports them as alive.
   */
  public List<ProcessHandle> children() {
    return process.children().filter(c -> !isZombie(c.pid())).toList();
  }

  /**
   * True when the process has exited but its parent has not collected it yet. Always false on
   * Windows.
   */
  static boolean isZombie(long pid) {
    return isZombie(Path.of("/proc"), pid);
  }

  /**
   * As above, with the proc filesystem's location injectable so the {@code ps} fallback can be
   * exercised on Linux.
   */
  static boolean isZombie(Path procRoot, long pid) {
    return isZombie(
        procRoot, pid, command -> new ProcessBuilder(command).redirectErrorStream(true).start());
  }

  /** Starts {@code ps}; injectable so tests can simulate it hanging or being interrupted. */
  interface Launcher {
    Process start(List<String> command) throws IOException;
  }

  static boolean isZombie(Path procRoot, long pid, Launcher launcher) {
    if (ShellCommand.isWindows()) return false;
    try {
      Path stat = procRoot.resolve(Long.toString(pid)).resolve("stat");
      if (Files.isReadable(
          stat)) { // Linux: the state letter follows the parenthesised command name
        String text = Files.readString(stat);
        int close = text.lastIndexOf(')');
        return close >= 0 && close + 2 < text.length() && text.charAt(close + 2) == 'Z';
      }
      Process ps =
          launcher.start(
              List.of("ps", "-o", "stat=", "-p", Long.toString(pid))); // macOS and other Unixes
      try {
        String state =
            new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return ps.waitFor(2, TimeUnit.SECONDS) && state.startsWith("Z");
      } finally {
        ps.destroyForcibly();
      }
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Writes one line to the process's stdin (used by the terminal). */
  public synchronized void send(String line) throws IOException {
    OutputStream out = process.getOutputStream();
    out.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  @Override
  public String toString() {
    return "#" + id + " " + name + ": " + command.get(command.size() - 1);
  }
}
