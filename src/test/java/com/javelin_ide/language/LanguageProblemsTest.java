package com.javelin_ide.language;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.build.Problem;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageProblemsTest {
    private static Lsp.Diagnostic d(int severity, String message, String source) {
        return new Lsp.Diagnostic(new Lsp.Range(new Lsp.Position(4, 2), new Lsp.Position(4, 6)), severity, message, source);
    }

    @Test void positionsBecomeOneBasedAndTheSourceIsAppended() {
        List<Problem> list = LanguageProblems.toProblems("src/A.java", List.of(d(Lsp.Diagnostic.ERROR, "cannot find symbol", "javac")));
        assertEquals(List.of(new Problem(Problem.Severity.ERROR, "src/A.java", 5, 3, "cannot find symbol  [javac]")), list);
    }

    @Test void onlyErrorsAreErrors() {
        List<Problem> list = LanguageProblems.toProblems("f", List.of(d(Lsp.Diagnostic.WARNING, "w", null), d(Lsp.Diagnostic.INFORMATION, "i", null), d(Lsp.Diagnostic.HINT, "h", null)));
        assertEquals(3, list.size());
        assertTrue(list.stream().allMatch(p -> p.severity() == Problem.Severity.WARNING));
    }

    @Test void messagesStayOnOneLine() {
        assertEquals("one two", LanguageProblems.toProblems("f", List.of(d(1, "one\ntwo", null))).get(0).message());
    }

    @Test void noDiagnosticsMeansNoProblems() {
        assertTrue(LanguageProblems.toProblems("f", List.of()).isEmpty());
    }
}
