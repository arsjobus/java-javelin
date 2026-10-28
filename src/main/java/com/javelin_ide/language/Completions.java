package com.javelin_ide.language;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The text arithmetic of code completion, shared by the editor and the tests (JavaFX-free): where each server
 * suggestion would be inserted, and where it lands once the user has kept typing while the list was open.
 */
public final class Completions {
    /** At most this many server suggestions are offered; the rest would only slow the popup down. */
    public static final int MAX_ITEMS = 500;

    private Completions() {}

    /** A suggestion and the range {@code [start, end)} of the document it replaces when chosen. */
    public record Candidate(Lsp.CompletionItem item, int start, int end) {}

    /** What to show: the candidates and the offset the popup filters from (the start of the word being completed). */
    public record Plan(List<Candidate> candidates, int popupStart) {}

    /** A replacement to apply to the document, as {@code [start, end)} and the new text. */
    public record Replacement(int start, int end, String text) {}

    /** The start of the identifier that ends at {@code caret}, which is where completion without an explicit edit begins. */
    public static int wordStart(String text, int caret) {
        int start = Math.max(0, Math.min(caret, text.length()));
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        return start;
    }

    /**
     * Places up to {@link #MAX_ITEMS} suggestions in {@code text}. A suggestion that carries its own edit replaces exactly
     * that range ({@code offsetOf} turns the server's line/character into an offset); the others replace the word before
     * the caret. The popup filters from the edit start of the last suggestion that has one, otherwise from the word start.
     */
    public static Plan plan(String text, int requestCaret, List<Lsp.CompletionItem> items, ToIntFunction<Lsp.Position> offsetOf) {
        int wordStart = wordStart(text, requestCaret);
        int popupStart = wordStart;
        List<Candidate> candidates = new ArrayList<>();
        for (Lsp.CompletionItem item : items.stream().limit(MAX_ITEMS).toList()) {
            int start = wordStart, end = requestCaret;
            if (item.edit() != null) {
                start = offsetOf.applyAsInt(item.edit().range().start());
                end = offsetOf.applyAsInt(item.edit().range().end());
                popupStart = start;
            }
            candidates.add(new Candidate(item, start, end));
        }
        return new Plan(List.copyOf(candidates), popupStart);
    }

    /**
     * What choosing {@code candidate} does now. The list was requested with the caret at {@code requestCaret}; the user may
     * have typed (or deleted) since, so the end moves with the caret, and the whole range is kept inside a document that
     * is now {@code documentLength} long (it can have shrunk below the candidate while the list was open).
     */
    public static Replacement replacement(Candidate candidate, int requestCaret, int caretNow, int documentLength) {
        int shift = caretNow - requestCaret;
        int start = Math.min(candidate.start(), documentLength);
        int end = Math.min(Math.max(start, candidate.end() + shift), documentLength);
        return new Replacement(start, end, candidate.item().text());
    }
}
