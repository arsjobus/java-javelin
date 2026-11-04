package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * A request's cancel button. The window calls {@link #cancel()} from the FX thread; the provider
 * registers what has to happen to stop its work (closing a connection) with {@link #onCancel} and
 * checks {@link #isCancelled()} between chunks. Safe to use from several threads.
 */
public final class AiCancel {
  private final List<Runnable> hooks = new ArrayList<>();
  private boolean cancelled;

  /** Cancels the request and runs every registered hook once. Calling it again does nothing. */
  public void cancel() {
    List<Runnable> run;
    synchronized (this) {
      if (cancelled) return;
      cancelled = true;
      run = new ArrayList<>(hooks);
      hooks.clear();
    }
    for (Runnable hook : run) hook.run();
  }

  public synchronized boolean isCancelled() {
    return cancelled;
  }

  /**
   * Runs {@code hook} when the request is cancelled; at once if it already is. Returns a handle
   * that withdraws the hook.
   */
  public Runnable onCancel(Runnable hook) {
    synchronized (this) {
      if (!cancelled) {
        hooks.add(hook);
        return () -> {
          synchronized (this) {
            hooks.remove(hook);
          }
        };
      }
    }
    hook.run();
    return () -> {};
  }

  public void throwIfCancelled() throws AiException {
    if (isCancelled())
      throw new AiException(AiException.Kind.CANCELLED, "The AI request was cancelled.");
  }
}
