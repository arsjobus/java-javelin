package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticShifterTest {
    private static Lsp.Position p(int line, int ch) { return new Lsp.Position(line, ch); }
    private static Lsp.Diagnostic diag(int l1, int c1, int l2, int c2) {
        return new Lsp.Diagnostic(new Lsp.Range(p(l1, c1), p(l2, c2)), Lsp.Diagnostic.ERROR, "msg", "src");
    }
    private static Lsp.Range only(List<Lsp.Diagnostic> list) { assertEquals(1, list.size()); return list.get(0).range(); }
    private static Lsp.Range range(int l1, int c1, int l2, int c2) { return new Lsp.Range(p(l1, c1), p(l2, c2)); }

    @Test void typingBeforeADiagnosticOnTheSameLineMovesItRight() {
        assertEquals(range(2, 13, 2, 18), only(DiagnosticShifter.shift(List.of(diag(2, 10, 2, 15)), p(2, 4), "", "abc")));
    }

    @Test void deletingBeforeADiagnosticOnTheSameLineMovesItLeft() {
        assertEquals(range(2, 8, 2, 13), only(DiagnosticShifter.shift(List.of(diag(2, 10, 2, 15)), p(2, 4), "xy", "")));
    }

    @Test void typingAfterADiagnosticChangesNothing() {
        assertEquals(range(2, 10, 2, 15), only(DiagnosticShifter.shift(List.of(diag(2, 10, 2, 15)), p(2, 20), "", "zzz")));
    }

    @Test void typingInsideADiagnosticStretchesIt() {
        assertEquals(range(0, 10, 0, 13), only(DiagnosticShifter.shift(List.of(diag(0, 10, 0, 12)), p(0, 11), "", "x")));
    }

    @Test void typingAtTheStartPushesTheDiagnosticOffTheNewText() {
        assertEquals(range(0, 11, 0, 16), only(DiagnosticShifter.shift(List.of(diag(0, 10, 0, 15)), p(0, 10), "", "x")));
    }

    @Test void typingAtTheEndDoesNotExtendTheDiagnostic() {
        assertEquals(range(0, 10, 0, 15), only(DiagnosticShifter.shift(List.of(diag(0, 10, 0, 15)), p(0, 15), "", "x")));
    }

    @Test void enterBeforeADiagnosticMovesItDownAWholeLine() {
        assertEquals(range(4, 3, 4, 8), only(DiagnosticShifter.shift(List.of(diag(3, 3, 3, 8)), p(1, 0), "", "\n")));
    }

    @Test void enterInTheMiddleOfALineMovesTheRestOfThatLineToTheNewLine() {
        // "abcdef" with a diagnostic on "def" (cols 3-6); Enter after "ab" -> "ab" / "cdef", diagnostic on the second line.
        assertEquals(range(1, 1, 1, 4), only(DiagnosticShifter.shift(List.of(diag(0, 3, 0, 6)), p(0, 2), "", "\n")));
    }

    @Test void enterInsideADiagnosticSplitsItAcrossTwoLines() {
        assertEquals(range(0, 3, 1, 2), only(DiagnosticShifter.shift(List.of(diag(0, 3, 0, 6)), p(0, 4), "", "\n")));
    }

    @Test void joiningLinesPullsALaterDiagnosticUp() {
        // Backspace at the start of line 1 removes the newline that ended line 0 (which is 5 chars long).
        assertEquals(range(0, 8, 0, 12), only(DiagnosticShifter.shift(List.of(diag(1, 3, 1, 7)), p(0, 5), "\n", "")));
    }

    @Test void deletingLinesAboveMovesADiagnosticUpByThatManyLines() {
        assertEquals(range(2, 0, 2, 4), only(DiagnosticShifter.shift(List.of(diag(5, 0, 5, 4)), p(1, 0), "a\nb\nc\n", "")));
    }

    @Test void pastingMultipleLinesMovesLaterDiagnosticsOnTheLastLineByColumnToo() {
        // Replace nothing at (1,2) with "x\ny\nzz"; a diagnostic at (1,5)-(1,7) follows the end of the paste.
        assertEquals(range(3, 5, 3, 7), only(DiagnosticShifter.shift(List.of(diag(1, 5, 1, 7)), p(1, 2), "", "x\ny\nzz")));
    }

    @Test void replacingTextKeepsItsDiagnosticOnTheReplacement() {
        assertEquals(range(0, 4, 0, 11), only(DiagnosticShifter.shift(List.of(diag(0, 4, 0, 7)), p(0, 4), "foo", "renamed")));
    }

    @Test void overwritingPartOfADiagnosticSnapsItsEdgesToTheEdit() {
        // Diagnostic 2..10; replace 6..14 (overlapping its end) by "ab".
        assertEquals(range(0, 2, 0, 8), only(DiagnosticShifter.shift(List.of(diag(0, 2, 0, 10)), p(0, 6), "12345678", "ab")));
    }

    @Test void aDiagnosticThatIsFullyDeletedIsDropped() {
        assertTrue(DiagnosticShifter.shift(List.of(diag(0, 4, 0, 7)), p(0, 2), "123456789", "").isEmpty());
    }

    @Test void aServerSuppliedEmptyRangeIsKept() {
        assertEquals(range(0, 6, 0, 6), only(DiagnosticShifter.shift(List.of(diag(0, 5, 0, 5)), p(0, 0), "", "x")));
    }

    @Test void everyDiagnosticIsShiftedAndTheMessageSeverityAndSourceSurvive() {
        Lsp.Diagnostic warning = new Lsp.Diagnostic(range(0, 0, 0, 1), Lsp.Diagnostic.WARNING, "careful", "lint");
        List<Lsp.Diagnostic> out = DiagnosticShifter.shift(List.of(warning, diag(3, 0, 3, 2)), p(0, 5), "", "\n");
        assertEquals(2, out.size());
        assertEquals(warning, out.get(0));
        assertEquals(range(4, 0, 4, 2), out.get(1).range());
        assertEquals("careful", out.get(0).message());
        assertEquals("src", out.get(1).source());
        assertEquals(Lsp.Diagnostic.ERROR, out.get(1).severity());
    }

    @Test void anEditThatChangesNothingReturnsTheSameDiagnostics() {
        List<Lsp.Diagnostic> list = List.of(diag(0, 0, 0, 3));
        assertSame(list, DiagnosticShifter.shift(list, p(0, 1), "", ""));
    }

    @Test void nullAndEmptyInputsGiveAnEmptyList() {
        assertTrue(DiagnosticShifter.shift(null, p(0, 0), "", "x").isEmpty());
        assertTrue(DiagnosticShifter.shift(List.of(), p(0, 0), "", "x").isEmpty());
    }

    @Test void shiftingTracksTheRealTextAcrossManyEdits() {
        // Simulate typing in a TextDocument-like string and check the diagnostic still covers the word "bad".
        StringBuilder text = new StringBuilder("int a;\nint bad;\n");
        List<Lsp.Diagnostic> list = List.of(diag(1, 4, 1, 7));
        // {offset, characters to remove, text to insert}
        String[][] edits = {{"0", "0", "// c\n"}, {"6", "0", "xx"}, {"2", "3", ""}, {"3", "0", "\n\n"}, {"0", "5", "q"}};
        for (String[] edit : edits) {
            int offset = Integer.parseInt(edit[0]), length = Integer.parseInt(edit[1]);
            String removed = text.substring(offset, offset + length);
            Lsp.Position start = positionAt(text, offset);
            text.replace(offset, offset + length, edit[2]);
            list = DiagnosticShifter.shift(list, start, removed, edit[2]);
            assertEquals("bad", slice(text, only(list)), "after edit at " + offset);
        }
    }

    private static Lsp.Position positionAt(CharSequence text, int offset) {
        int line = 0, lineStart = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') { line++; lineStart = i + 1; }
        return new Lsp.Position(line, offset - lineStart);
    }

    private static String slice(CharSequence text, Lsp.Range r) {
        return text.subSequence(offsetOf(text, r.start()), offsetOf(text, r.end())).toString();
    }

    private static int offsetOf(CharSequence text, Lsp.Position pos) {
        int line = 0, i = 0;
        while (line < pos.line()) { if (text.charAt(i++) == '\n') line++; }
        return i + pos.character();
    }
}
