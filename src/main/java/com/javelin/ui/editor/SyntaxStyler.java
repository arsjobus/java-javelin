package com.javelin.ui.editor;

import com.javelin.editor.SyntaxHighlighter;
import com.javelin.language.DiagnosticStore;
import com.javelin.language.Lsp;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Colours an editor's text by language and underlines the language server's diagnostics. */
final class SyntaxStyler {
    private final DiagnosticStore diagnostics;
    private final Function<CodeArea, File> fileOf;

    SyntaxStyler(DiagnosticStore diagnostics, Function<CodeArea, File> fileOf) {
        this.diagnostics = diagnostics;
        this.fileOf = fileOf;
    }

    void highlight(CodeArea a) {
        String text = a.getText();
        if (text.isEmpty()) return; // StyleSpansBuilder refuses to build zero spans
        if (SyntaxHighlighter.tooLarge(text.length())) { a.clearStyle(0, text.length()); return; } // keeps typing responsive in huge files
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>(); int last = 0;
        for (SyntaxHighlighter.Span span : SyntaxHighlighter.spans(text, fileOf.apply(a))) {
            if (span.start() > last) b.add(Collections.emptyList(), span.start() - last);
            b.add(Collections.singleton(span.style()), span.end() - span.start()); last = span.end();
        }
        if (last < text.length()) b.add(Collections.emptyList(), text.length() - last);
        StyleSpans<Collection<String>> spans = b.create();
        List<Lsp.Diagnostic> reported = diagnostics.get(fileOf.apply(a));
        if (reported != null && !reported.isEmpty() && text.length() > 0) {
            spans = spans.overlay(diagnosticSpans(a, reported, text.length()), (base, extra) -> { List<String> merged = new ArrayList<>(base); merged.addAll(extra); return merged; });
        }
        a.setStyleSpans(0, spans);
    }

    /** Underline styles for the given diagnostics, one span per non-overlapping range. */
    private StyleSpans<Collection<String>> diagnosticSpans(CodeArea a, List<Lsp.Diagnostic> reported, int length) {
        List<int[]> ranges = new ArrayList<>(); // {start, end, severity}
        for (Lsp.Diagnostic d : reported) {
            int start = EditorPositions.offsetOf(a, d.range().start()), end = EditorPositions.offsetOf(a, d.range().end());
            if (end <= start) { if (start < length) end = start + 1; else if (start > 0) { end = start; start--; } else continue; }
            ranges.add(new int[] {start, end, d.severity()});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int at = 0;
        for (int[] r : ranges) {
            int start = Math.max(r[0], at), end = Math.min(r[1], length);
            if (end <= start) continue;
            if (start > at) b.add(Collections.emptyList(), start - at);
            b.add(Collections.singleton(r[2] == Lsp.Diagnostic.ERROR ? "diag-error" : "diag-warning"), end - start);
            at = end;
        }
        if (at < length) b.add(Collections.emptyList(), length - at);
        return b.create();
    }
}
