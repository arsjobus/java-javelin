package com.javelin.build;

/** A diagnostic recovered from build output. Line and column are 1-based; 0 means unknown. */
public record Problem(Severity severity, String file, int line, int column, String message) {
    public enum Severity { ERROR, WARNING }

    @Override public String toString() {
        String where = line > 0 ? file + ":" + line + (column > 0 ? ":" + column : "") : file;
        return severity.name().charAt(0) + " " + where + "  " + message;
    }
}
