package com.javelin_ide.language;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompletionsTest {
    private static Lsp.CompletionItem plain(String label) { return new Lsp.CompletionItem(label, null, 0, null, null, null, false); }

    private static Lsp.CompletionItem withEdit(String label, int line, int from, int to, String newText) {
        Lsp.Range range = new Lsp.Range(new Lsp.Position(line, from), new Lsp.Position(line, to));
        return new Lsp.CompletionItem(label, null, 0, null, null, new Lsp.TextEdit(range, newText), false);
    }

    /** Offsets for a one-line document, which is all these tests need. */
    private static int offset(Lsp.Position position) { return position.character(); }

    @Test void theWordBeforeTheCaretIsWhereCompletionStarts() {
        assertEquals(4, Completions.wordStart("int fo", 6));
        assertEquals(4, Completions.wordStart("int fo", 5), "caret inside the word");
        assertEquals(7, Completions.wordStart("int fo ", 7), "a space before the caret: no word, so the caret itself");
    }

    @Test void identifierCharactersAreLettersDigitsUnderscoreAndDollar() {
        assertEquals(0, Completions.wordStart("a_b$9", 5));
        assertEquals(2, Completions.wordStart("x.name", 6), "a dot ends the word");
    }

    @Test void noWordMeansTheCaretItself() {
        assertEquals(4, Completions.wordStart("foo ", 4));
        assertEquals(0, Completions.wordStart("", 0));
        assertEquals(2, Completions.wordStart("x.", 2));
    }

    @Test void aCaretOutsideTheTextIsClamped() {
        assertEquals(0, Completions.wordStart("abc", 99));
        assertEquals(0, Completions.wordStart("abc", -5));
    }

    @Test void suggestionsWithoutAnEditReplaceTheWordBeforeTheCaret() {
        Completions.Plan plan = Completions.plan("int fo", 6, List.of(plain("foo"), plain("for")), CompletionsTest::offset);
        assertEquals(4, plan.popupStart());
        assertEquals(2, plan.candidates().size());
        assertEquals(new Completions.Candidate(plain("foo"), 4, 6), plan.candidates().get(0));
        assertEquals(new Completions.Candidate(plain("for"), 4, 6), plan.candidates().get(1));
    }

    @Test void aSuggestionWithAnEditReplacesExactlyThatRange() {
        Lsp.CompletionItem item = withEdit("System", 0, 0, 3, "System");
        Completions.Plan plan = Completions.plan("Sys", 3, List.of(item), CompletionsTest::offset);
        assertEquals(new Completions.Candidate(item, 0, 3), plan.candidates().get(0));
        assertEquals(0, plan.popupStart());
    }

    @Test void thePopupFiltersFromTheLastEditThatIsGivenAndMixedListsWork() {
        Lsp.CompletionItem first = withEdit("a", 0, 2, 5, "a");
        Lsp.CompletionItem second = withEdit("b", 0, 3, 5, "b");
        Completions.Plan plan = Completions.plan("x.foo", 5, List.of(first, plain("c"), second), CompletionsTest::offset);
        assertEquals(3, plan.popupStart());
        assertEquals(2, plan.candidates().get(0).start());
        assertEquals(2, plan.candidates().get(1).start(), "the plain one still replaces the word before the caret: foo starts at 2");
        assertEquals(3, plan.candidates().get(2).start());
    }

    @Test void positionsAreConvertedWithTheCallersOffsetFunction() {
        Lsp.CompletionItem item = withEdit("x", 3, 1, 2, "x");
        List<Lsp.Position> asked = new ArrayList<>();
        Completions.plan("text", 4, List.of(item), position -> { asked.add(position); return 40 + position.line(); });
        assertEquals(List.of(new Lsp.Position(3, 1), new Lsp.Position(3, 2)), asked, "start, then end");
    }

    @Test void onlyTheFirstFiveHundredSuggestionsAreOffered() {
        List<Lsp.CompletionItem> items = new ArrayList<>();
        for (int i = 0; i < 700; i++) items.add(plain("item" + i));
        Completions.Plan plan = Completions.plan("i", 1, items, CompletionsTest::offset);
        assertEquals(Completions.MAX_ITEMS, plan.candidates().size());
        assertEquals("item499", plan.candidates().get(499).item().label());
        assertThrows(UnsupportedOperationException.class, () -> plan.candidates().add(null), "the plan is read-only");
    }

    @Test void noSuggestionsMakeAnEmptyPlan() {
        Completions.Plan plan = Completions.plan("abc", 3, List.of(), CompletionsTest::offset);
        assertTrue(plan.candidates().isEmpty());
        assertEquals(0, plan.popupStart());
    }

    @Test void choosingWithoutTypingReplacesTheCandidateRange() {
        Completions.Candidate candidate = new Completions.Candidate(plain("foo"), 4, 6);
        assertEquals(new Completions.Replacement(4, 6, "foo"), Completions.replacement(candidate, 6, 6, 6));
    }

    @Test void charactersTypedWhileTheListWasOpenAreReplacedToo() {
        Completions.Candidate candidate = new Completions.Candidate(plain("foobar"), 4, 6);
        assertEquals(new Completions.Replacement(4, 8, "foobar"), Completions.replacement(candidate, 6, 8, 20), "typed two more");
        assertEquals(new Completions.Replacement(4, 5, "foobar"), Completions.replacement(candidate, 6, 5, 20), "deleted one");
    }

    @Test void theReplacementNeverStartsBeforeItsStartOrRunsPastTheDocument() {
        Completions.Candidate candidate = new Completions.Candidate(plain("x"), 4, 6);
        assertEquals(new Completions.Replacement(4, 4, "x"), Completions.replacement(candidate, 6, 0, 20), "everything typed was deleted: an insert");
        assertEquals(new Completions.Replacement(4, 7, "x"), Completions.replacement(candidate, 6, 30, 7), "clamped to the document");
    }

    @Test void aDocumentThatShrankBelowTheCandidateGetsAnInsertAtItsEndInsteadOfAnInvalidRange() {
        Completions.Candidate candidate = new Completions.Candidate(plain("x"), 10, 12);
        assertEquals(new Completions.Replacement(3, 3, "x"), Completions.replacement(candidate, 12, 12, 3));
    }

    @Test void snippetPlaceholdersAreFlattenedInTheInsertedText() {
        Lsp.CompletionItem snippet = new Lsp.CompletionItem("println", null, 0, "println(${1:value})", null, null, true);
        Completions.Candidate candidate = new Completions.Candidate(snippet, 0, 0);
        assertEquals("println(value)", Completions.replacement(candidate, 0, 0, 0).text());
    }
}
