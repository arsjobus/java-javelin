package com.javelin.ai;

/**
 * Why an AI request did not produce an answer. The message is written for the person using the editor (it says what to
 * do next), so the window can show it as it is. An AI failure never affects ordinary editing: it is only reported.
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
        /** The request was refused before anything was sent (no model chosen, a secret file, a remote address...). */
        REFUSED
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;

    public AiException(Kind kind, String message) { super(message); this.kind = kind; }

    public AiException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }

    public Kind kind() { return kind; }
}
