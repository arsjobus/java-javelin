package com.javelin.build;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.build.Problem.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProblemParserTest {
    private static Problem only(String line) {
        Optional<Problem> p = ProblemParser.parse(line);
        assertTrue(p.isPresent(), "expected a problem in: " + line);
        return p.get();
    }

    private static List<Problem> feed(String... lines) {
        ProblemParser parser = new ProblemParser();
        List<Problem> out = new ArrayList<>();
        for (String line : lines) parser.accept(line).ifPresent(out::add);
        return out;
    }

    @Test void javac() {
        assertEquals(new Problem(Severity.ERROR, "src/Foo.java", 12, 0, "cannot find symbol"), only("src/Foo.java:12: error: cannot find symbol"));
    }

    @Test void gccWithColumnAndFatal() {
        assertEquals(new Problem(Severity.ERROR, "a.c", 3, 5, "stdio.h: No such file"), only("a.c:3:5: fatal error: stdio.h: No such file"));
        assertEquals(Severity.WARNING, only("a.c:3:5: warning: unused variable").severity());
    }

    @Test void mavenWithAndWithoutColumn() {
        assertEquals(new Problem(Severity.ERROR, "/p/Foo.java", 12, 5, "cannot find symbol"), only("[ERROR] /p/Foo.java:[12,5] cannot find symbol"));
        assertEquals(new Problem(Severity.WARNING, "/p/Foo.java", 7, 0, "deprecated"), only("[WARNING] /p/Foo.java:[7] deprecated"));
    }

    @Test void windowsDriveLettersSurviveInEveryFormat() {
        assertEquals("C:\\p\\Foo.java", only("[ERROR] C:\\p\\Foo.java:[1,2] x").file());
        assertEquals("C:\\p\\Foo.java", only("C:\\p\\Foo.java:1: error: x").file());
        assertEquals("C:\\p\\a.go", only("C:\\p\\a.go:4:2: undefined: y").file());
    }

    @Test void typescriptAndMsvc() {
        assertEquals(new Problem(Severity.ERROR, "src/app.ts", 12, 5, "Type 'x' is not assignable"), only("src/app.ts(12,5): error TS2322: Type 'x' is not assignable"));
        assertEquals(new Problem(Severity.WARNING, "a.cpp", 9, 0, "unreferenced"), only("a.cpp(9): warning C4100: unreferenced"));
        assertEquals(5, only("a.cpp(9, 5): error C2065: undeclared").column());
    }

    @Test void kotlinBothFormats() {
        assertEquals(new Problem(Severity.ERROR, "/p/A.kt", 3, 9, "Unresolved reference: x"), only("e: file:///p/A.kt:3:9 Unresolved reference: x"));
        assertEquals(new Problem(Severity.WARNING, "/p/A.kt", 4, 1, "Variable unused"), only("w: /p/A.kt: (4, 1): Variable unused"));
    }

    @Test void goHasNoSeverityKeyword() {
        assertEquals(new Problem(Severity.ERROR, "./main.go", 12, 5, "undefined: x"), only("./main.go:12:5: undefined: x"));
        assertEquals(0, only("./main.go:12: undefined: x").column());
    }

    @Test void unrecognizedLinesAreIgnored() {
        for (String s : new String[] {"", "BUILD SUCCESS", "Compiling 3 files", "note: see above", "[INFO] x:[1,2] nope"}) assertTrue(ProblemParser.parse(s).isEmpty(), s);
    }

    @Test void messagesAreTrimmedAndFilesToo() {
        assertEquals("msg", only("a.c:1: error:   msg   ").message());
    }

    @Test void statefulParserHandlesSingleLineFormats() {
        assertEquals(1, feed("a.c:1: error: x").size());
    }

    @Test void rustLocationFollowsHeadline() {
        List<Problem> found = feed("error[E0425]: cannot find value `x` in this scope", " --> src/main.rs:2:5", "  |", "warning: unused variable: `y`", "  --> src/lib.rs:7:9");
        assertEquals(List.of(new Problem(Severity.ERROR, "src/main.rs", 2, 5, "cannot find value `x` in this scope"),
            new Problem(Severity.WARNING, "src/lib.rs", 7, 9, "unused variable: `y`")), found);
    }

    @Test void rustHeadlineOnlyPairsWithTheNextLine() {
        assertTrue(feed("error: aborting due to 1 previous error", "something else", " --> src/main.rs:1:1").isEmpty());
        assertTrue(feed(" --> src/main.rs:1:1").isEmpty());
    }

    @Test void rustHeadlineFollowedByAnotherHeadlineKeepsTheNewOne() {
        List<Problem> found = feed("error: first", "error[E1]: second", " --> a.rs:1:2");
        assertEquals(1, found.size());
        assertEquals("second", found.get(0).message());
    }

    @Test void pythonTracebackUsesInnermostFrame() {
        List<Problem> found = feed("Traceback (most recent call last):", "  File \"/p/main.py\", line 10, in <module>", "    run()",
            "  File \"/p/lib.py\", line 4, in run", "    1/0", "ZeroDivisionError: division by zero");
        assertEquals(List.of(new Problem(Severity.ERROR, "/p/lib.py", 4, 0, "ZeroDivisionError: division by zero")), found);
    }

    @Test void pythonSyntaxErrorHasNoTracebackHeader() {
        List<Problem> found = feed("  File \"x.py\", line 1", "    foo bar", "        ^", "SyntaxError: invalid syntax");
        assertEquals(1, found.size());
        assertEquals(1, found.get(0).line());
    }

    @Test void pythonErrorWithoutFrameIsNotAProblem() {
        assertTrue(feed("ValueError: not from a traceback").isEmpty());
    }

    @Test void pythonErrorIsReportedOnlyOncePerTraceback() {
        assertEquals(1, feed("  File \"x.py\", line 1, in <module>", "KeyError: 'a'", "KeyError: 'a'").size());
    }

    @Test void newTracebackForgetsAnUnfinishedPreviousOne() {
        assertTrue(feed("  File \"x.py\", line 1, in <module>", "Traceback (most recent call last):", "ValueError: late").isEmpty());
    }

    @Test void problemToString() {
        assertEquals("E a.c:3:5  boom", new Problem(Severity.ERROR, "a.c", 3, 5, "boom").toString());
        assertEquals("W a.c:3  boom", new Problem(Severity.WARNING, "a.c", 3, 0, "boom").toString());
        assertEquals("E a.c  boom", new Problem(Severity.ERROR, "a.c", 0, 0, "boom").toString());
    }

    @Test void veryLongLinesDoNotBlowUpTheRegexes() {
        String junk = "x".repeat(200_000);
        long start = System.nanoTime();
        assertTrue(ProblemParser.parse(junk).isEmpty());
        assertTrue(ProblemParser.parse(junk + ":1: error: " + junk).isPresent());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "parsing a 400 KB line took too long");
    }
}
