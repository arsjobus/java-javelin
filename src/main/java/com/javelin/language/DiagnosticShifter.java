package com.javelin.language;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps language-server diagnostics attached to the text they were reported for while the user types.
 *
 * <p>A server publishes diagnostics for one version of the document, a moment after the last edit. Until it
 * publishes again, every edit moves the text under the stored line/column ranges, so the underlines would sit a few
 * characters off. {@link #shift} moves the ranges along with each edit instead. JavaFX-free so it can be unit tested.
 *
 * <p>Rules, for an edit that replaces {@code removed} with {@code inserted} at {@code start}:
 * <ul>
 *   <li>A point before the edit stays; a point after it moves by the size of the change (including line breaks).
 *   <li>Text typed at the very start of a diagnostic is not underlined; text typed at the very end does not extend it.
 *       Text typed inside it does extend it.
 *   <li>A point inside the replaced text snaps to the start of the edit (range start) or to the end of the inserted
 *       text (range end), so replaced text stays underlined until the server answers.
 *   <li>A diagnostic whose whole non-empty range was deleted is dropped. Empty ranges sent by a server are kept.
 * </ul>
 */
public final class DiagnosticShifter {
    private DiagnosticShifter() {}

    /** Shifts every diagnostic for one edit; {@code start} is where the edit begins, in the text before and after it alike. */
    public static List<Lsp.Diagnostic> shift(List<Lsp.Diagnostic> diagnostics, Lsp.Position start, String removed, String inserted) {
        if (diagnostics == null || diagnostics.isEmpty()) return diagnostics == null ? List.of() : diagnostics;
        if (removed.isEmpty() && inserted.isEmpty()) return diagnostics;
        Lsp.Position oldEnd = advance(start, removed), newEnd = advance(start, inserted);
        List<Lsp.Diagnostic> out = new ArrayList<>(diagnostics.size());
        for (Lsp.Diagnostic d : diagnostics) {
            Lsp.Position s = d.range().start(), e = d.range().end();
            boolean wasEmpty = compare(s, e) >= 0;
            Lsp.Position ns = mapStart(s, start, oldEnd, newEnd), ne = mapEnd(e, start, oldEnd, newEnd);
            if (compare(ne, ns) < 0) ne = ns;
            if (!wasEmpty && compare(ns, ne) == 0) continue;
            out.add(new Lsp.Diagnostic(new Lsp.Range(ns, ne), d.severity(), d.message(), d.source()));
        }
        return out;
    }

    /** The position reached by writing {@code text} starting at {@code from}. */
    static Lsp.Position advance(Lsp.Position from, String text) {
        int lines = 0, last = -1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') { lines++; last = i; }
        if (lines == 0) return new Lsp.Position(from.line(), from.character() + text.length());
        return new Lsp.Position(from.line() + lines, text.length() - last - 1);
    }

    static int compare(Lsp.Position a, Lsp.Position b) {
        return a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.character(), b.character());
    }

    private static Lsp.Position mapStart(Lsp.Position p, Lsp.Position start, Lsp.Position oldEnd, Lsp.Position newEnd) {
        if (compare(p, start) < 0) return p;
        if (compare(p, oldEnd) >= 0) return after(p, oldEnd, newEnd);
        return start;
    }

    private static Lsp.Position mapEnd(Lsp.Position p, Lsp.Position start, Lsp.Position oldEnd, Lsp.Position newEnd) {
        if (compare(p, start) <= 0) return p;
        if (compare(p, oldEnd) >= 0) return after(p, oldEnd, newEnd);
        return newEnd;
    }

    /** A point at or after the end of the replaced text, moved to where that text now ends. */
    private static Lsp.Position after(Lsp.Position p, Lsp.Position oldEnd, Lsp.Position newEnd) {
        if (p.line() == oldEnd.line()) return new Lsp.Position(newEnd.line(), newEnd.character() + p.character() - oldEnd.character());
        return new Lsp.Position(p.line() + newEnd.line() - oldEnd.line(), p.character());
    }
}
