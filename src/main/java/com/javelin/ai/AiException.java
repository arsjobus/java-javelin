package com.javelin.ai;

/**
 * Why an AI request did not produce an answer. The message is written for the person using the
 * editor (it says what to do next), so the window can show it as it is. An AI failure never affects
 * ordinary editing: it is only reported.
 */
public final class AiException extends Exception {
  public enum Kind {
    /** The provider could not be reached (for Ollama: it is not running). */
    UNAVAILABLE,
    /** The chosen model is not installed on the provider. */
    MODEL_MISSING,
    /** The provider stopped answering. */
    TIMEOUT,
    /** The user cancelled the request. */
    CANCELLED,
    /** The provider answered with something that cannot be used. */
    BAD_RESPONSE,
    /**
     * The request was refused before anything was sent (no model chosen, a secret file, a remote
     * address...).
     */
    REFUSED
  }

  private static final long serialVersionUID = 1L;

  private final Kind kind;
  private final FailureReason reason;

  public AiException(Kind kind, String message) {
    this(kind, null, message);
  }

  /** An exception that also says which of the shared failure reasons it is. */
  public AiException(Kind kind, FailureReason reason, String message) {
    super(message);
    this.kind = kind;
    this.reason = reason;
  }

  public AiException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.reason = null;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * Why this counts as a failure of the model, or null when it is not one (the provider is not
   * running, the model is not installed, the user cancelled). When the thrower did not say, it is
   * worked out from the kind: a refusal is {@link FailureReason#REFUSED}, a silence is {@link
   * FailureReason#TIMEOUT} and an unusable answer is {@link FailureReason#UNPARSEABLE}.
   */
  public FailureReason reason() {
    if (reason != null) return reason;
    return switch (kind) {
      case REFUSED -> FailureReason.REFUSED;
      case TIMEOUT -> FailureReason.TIMEOUT;
      case BAD_RESPONSE -> FailureReason.UNPARSEABLE;
      default -> null;
    };
  }
}
