package com.javelin.build;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes compiler diagnostics in build output. Unrecognized lines are ignored.
 *
 * <p>Single-line formats (javac, Maven, gcc/clang, tsc/MSVC, Kotlin, Go) are handled by the static
 * {@link #parse(String)}. Some tools spread one diagnostic over several lines (rustc/cargo put the
 * location on the line after the message; Python prints the location in a traceback frame and the
 * message last), so use one instance per process and feed it every line, in order, via
 * {@link #accept(String)}. An instance is not thread-safe.
 */
public final class ProblemParser {
    // [ERROR] /path/Foo.java:[12,5] message   (maven-compiler-plugin)
    private static final Pattern MAVEN = Pattern.compile("^\\[(ERROR|WARNING)\\]\\s+(.+?):\\[(\\d+)(?:,(\\d+))?\\]\\s+(.*)$");
    // /path/Foo.java:12: error: message   or   file:12:5: warning: message   (javac, gcc, clang, ...)
    private static final Pattern GENERIC = Pattern.compile("^(.+?):(\\d+):(?:(\\d+):)?\\s+(?:fatal\\s+)?(error|warning):\\s+(.*)$");
    // src/app.ts(12,5): error TS2322: message   or   file.cpp(12): warning C4100: message   (tsc, MSVC)
    private static final Pattern PAREN = Pattern.compile("^(.+?)\\((\\d+)(?:,\\s*(\\d+))?\\):\\s+(error|warning)\\s+[A-Za-z]+\\d+:\\s+(.*)$");
    // e: file:///path/Foo.kt:12:5 message   (Kotlin, current format)
    private static final Pattern KOTLIN = Pattern.compile("^([ew]): (?:file://)?(.+?):(\\d+):(\\d+)\\s+(.*)$");
    // e: /path/Foo.kt: (12, 5): message   (Kotlin, older format)
    private static final Pattern KOTLIN_OLD = Pattern.compile("^([ew]): (.+?): \\((\\d+), (\\d+)\\): (.*)$");
    // ./main.go:12:5: undefined: x   (go build/vet: no severity keyword, so everything is an error)
    private static final Pattern GO = Pattern.compile("^(.+\\.go):(\\d+):(?:(\\d+):)?\\s+(.*)$");

    // error[E0425]: cannot find value `x` in this scope   (rustc), followed by:   --> src/main.rs:2:5
    private static final Pattern RUST_HEAD = Pattern.compile("^(error|warning)(?:\\[[A-Za-z0-9]+\\])?:\\s+(.*)$");
    private static final Pattern RUST_AT = Pattern.compile("^\\s*--> (.+?):(\\d+):(\\d+)\\s*$");
    //   File "/path/app.py", line 12, in <module>   (Python traceback frame; the last one before the error is the innermost)
    private static final Pattern PY_FRAME = Pattern.compile("^\\s*File \"(.+)\", line (\\d+)(?:, in .*)?$");
    // NameError: name 'x' is not defined   (the exception line that ends a traceback or syntax error)
    private static final Pattern PY_ERROR = Pattern.compile("^([A-Za-z_][\\w.]*(?:Error|Exception)): (.*)$");

    private Problem.Severity rustSeverity;
    private String rustMessage;
    private String pythonFile;
    private int pythonLine;

    /** Stateless: recognizes only diagnostics that fit on one line. */
    public static Optional<Problem> parse(String line) {
        Matcher m = MAVEN.matcher(line);
        if (m.matches()) return Optional.of(build(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)));
        m = GENERIC.matcher(line);
        if (m.matches()) return Optional.of(build(m.group(4), m.group(1), m.group(2), m.group(3), m.group(5)));
        m = PAREN.matcher(line);
        if (m.matches()) return Optional.of(build(m.group(4), m.group(1), m.group(2), m.group(3), m.group(5)));
        m = KOTLIN.matcher(line);
        if (m.matches()) return Optional.of(build(kotlinSeverity(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5)));
        m = KOTLIN_OLD.matcher(line);
        if (m.matches()) return Optional.of(build(kotlinSeverity(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5)));
        m = GO.matcher(line);
        if (m.matches()) return Optional.of(build("error", m.group(1), m.group(2), m.group(3), m.group(4)));
        return Optional.empty();
    }

    /** Stateful: feed every line of one process's output in order. Handles multi-line formats as well as {@link #parse}. */
    public Optional<Problem> accept(String line) {
        // rustc: the location is on the line right after the headline, so a headline is only valid for one line.
        Problem.Severity severity = rustSeverity;
        String message = rustMessage;
        rustSeverity = null; rustMessage = null;
        Matcher m = RUST_AT.matcher(line);
        if (severity != null && m.matches()) {
            return Optional.of(new Problem(severity, m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), message));
        }
        m = RUST_HEAD.matcher(line);
        if (m.matches()) {
            rustSeverity = severity(m.group(1));
            rustMessage = m.group(2).trim();
            return Optional.empty();
        }

        if (line.startsWith("Traceback (most recent call last)")) { pythonFile = null; return Optional.empty(); }
        m = PY_FRAME.matcher(line);
        if (m.matches()) { pythonFile = m.group(1); pythonLine = Integer.parseInt(m.group(2)); return Optional.empty(); }
        m = PY_ERROR.matcher(line);
        if (m.matches() && pythonFile != null) {
            Problem problem = new Problem(Problem.Severity.ERROR, pythonFile, pythonLine, 0, m.group(1) + ": " + m.group(2).trim());
            pythonFile = null;
            return Optional.of(problem);
        }

        return parse(line);
    }

    private static String kotlinSeverity(String letter) { return letter.equals("e") ? "error" : "warning"; }

    private static Problem.Severity severity(String word) {
        return word.equalsIgnoreCase("error") ? Problem.Severity.ERROR : Problem.Severity.WARNING;
    }

    private static Problem build(String severity, String file, String line, String column, String message) {
        return new Problem(severity(severity), file.trim(), Integer.parseInt(line), column == null ? 0 : Integer.parseInt(column), message.trim());
    }
}
