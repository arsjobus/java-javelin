package com.javelin.ai;

/**
 * One result of checking a proposed change before the user sees it. An {@link Severity#ERROR} means the change is very
 * likely broken (it would not parse, it leaves a placeholder where code was, its brackets no longer balance); a
 * {@link Severity#WARNING} means it deserves a closer look (a lot of lines are gone, a method is no longer declared).
 */
public record Finding(Severity severity, String message) {
    public enum Severity { ERROR, WARNING }

    public static Finding error(String message) { return new Finding(Severity.ERROR, message); }

    public static Finding warning(String message) { return new Finding(Severity.WARNING, message); }

    public boolean isError() { return severity == Severity.ERROR; }

    /** One line for the review dialog. */
    public String label() { return (isError() ? "PROBLEM: " : "Check: ") + message; }
}
