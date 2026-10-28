package com.javelin_ide.language;

import com.javelin_ide.build.Problem;
import java.util.ArrayList;
import java.util.List;

/** Turns language-server diagnostics into entries for the Problems panel. JavaFX-free. */
public final class LanguageProblems {
    private LanguageProblems() {}

    /** One problem per diagnostic: errors stay errors, everything else is a warning; lines and columns become 1-based. */
    public static List<Problem> toProblems(String displayPath, List<Lsp.Diagnostic> diagnostics) {
        List<Problem> list = new ArrayList<>();
        for (Lsp.Diagnostic d : diagnostics) {
            Problem.Severity severity = d.severity() == Lsp.Diagnostic.ERROR ? Problem.Severity.ERROR : Problem.Severity.WARNING;
            String text = d.source() == null ? d.message() : d.message() + "  [" + d.source() + "]";
            list.add(new Problem(severity, displayPath, d.range().start().line() + 1, d.range().start().character() + 1, text.replace('\n', ' ')));
        }
        return list;
    }
}
