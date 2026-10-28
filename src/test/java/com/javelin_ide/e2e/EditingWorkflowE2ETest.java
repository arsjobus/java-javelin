package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.editor.Brackets;
import com.javelin_ide.editor.LineNavigation;
import com.javelin_ide.editor.TextSearch;
import com.javelin_ide.io.FileIO;
import com.javelin_ide.language.Completions;
import com.javelin_ide.language.Lsp;
import com.javelin_ide.language.TextEdits;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Moving around and editing a real file, the way the window does it: open it, jump to a line, match a bracket, find
 * and replace, accept a completion, save, reopen. The window only applies what these classes decide, so a failure here
 * reads as "this editing feature no longer works".
 */
class EditingWorkflowE2ETest {
    @TempDir Path home;

    private static final String SOURCE = "class Greeter {\n    String greet(String name) {\n        return \"Hello, \" + name;\n    }\n}\n";

    private Path savedSource() throws IOException {
        Path file = home.resolve("Greeter.java");
        FileIO.writeAtomic(file, SOURCE);
        return file;
    }

    @Test void goToLineLandsOnTheTypedLineAndRejectsNonsense() throws IOException {
        String text = FileIO.readText(savedSource());
        int lines = text.split("\n", -1).length; // the editor counts the empty last line too
        assertEquals(6, lines);
        assertEquals(3, LineNavigation.parseLine(" 3 ", lines).orElseThrow());
        assertTrue(LineNavigation.parseLine("7", lines).isEmpty());
        LineNavigation.Position target = LineNavigation.clamp(3, 9, lines, line -> text.split("\n", -1)[line].length());
        assertEquals(new LineNavigation.Position(2, 8), target);
    }

    @Test void aProblemReportedPastTheEndOfAShortenedFileStillOpensSomewhereSensible() throws IOException {
        String text = FileIO.readText(savedSource());
        LineNavigation.Position target = LineNavigation.clamp(40, 200, 6, line -> text.split("\n", -1)[line].length());
        assertEquals(new LineNavigation.Position(5, 0), target, "the last (empty) line");
    }

    @Test void matchingABracketSelectsTheWholeBlock() throws IOException {
        String text = FileIO.readText(savedSource());
        int open = text.indexOf("{");
        Brackets.Pair pair = Brackets.pair(text, Brackets.near(text, open).orElseThrow()).orElseThrow();
        assertEquals(text.lastIndexOf("}") + 1, pair.end(), "the class body runs to the last closing brace");
        assertEquals(open, pair.start());
        assertEquals(open, Brackets.pair(text, pair.partner()).orElseThrow().partner(), "and back again");
    }

    @Test void findNextWalksThroughMatchesAndReplaceSavesTheResult() throws IOException {
        Path file = savedSource();
        String text = FileIO.readText(file);
        TextSearch.Match first = TextSearch.first(text, "name", 0, true);
        TextSearch.Match second = TextSearch.next(text, "name", first.start(), first.end(), true);
        assertNotEquals(first, second);
        assertEquals(first, TextSearch.next(text, "name", second.start(), second.end(), true), "it wraps around");

        FileIO.writeAtomic(file, TextSearch.replace(text, "name", "who", true));
        assertEquals(SOURCE.replace("name", "who"), FileIO.readText(file));
    }

    @Test void acceptingACompletionReplacesThePrefixEvenIfTheUserKeptTyping() throws IOException {
        String text = FileIO.readText(savedSource());
        int caret = text.indexOf("(String name)") + "(String ".length() + 2; // the list is requested with "na|me" typed so far
        String prefixOnly = text.substring(0, caret) + text.substring(caret + 2);   // the document as it was: "...(String na) {"
        Lsp.CompletionItem item = new Lsp.CompletionItem("name2", "String", 0, null, null, null, false);
        Completions.Plan plan = Completions.plan(prefixOnly, caret, List.of(item), position -> TextEdits.offset(prefixOnly, position));
        Completions.Candidate candidate = plan.candidates().get(0);
        assertEquals(caret - 2, candidate.start());

        String typedMore = prefixOnly.substring(0, caret) + "m" + prefixOnly.substring(caret); // one more character while the list was open
        Completions.Replacement replacement = Completions.replacement(candidate, caret, caret + 1, typedMore.length());
        String result = typedMore.substring(0, replacement.start()) + replacement.text() + typedMore.substring(replacement.end());
        assertTrue(result.contains("greet(String name2)"), result);
    }

    @Test void aServerSuppliedEditIsAppliedExactlyWhereItSays() throws IOException {
        String text = FileIO.readText(savedSource());
        Lsp.Range range = new Lsp.Range(new Lsp.Position(1, 11), new Lsp.Position(1, 16)); // "greet"
        Lsp.CompletionItem item = new Lsp.CompletionItem("greetAll", null, 0, null, null, new Lsp.TextEdit(range, "greetAll"), false);
        Completions.Plan plan = Completions.plan(text, text.indexOf("greet") + 5, List.of(item), position -> TextEdits.offset(text, position));
        Completions.Replacement replacement = Completions.replacement(plan.candidates().get(0), text.indexOf("greet") + 5, text.indexOf("greet") + 5, text.length());
        assertEquals("    String greetAll(String name) {", (text.substring(0, replacement.start()) + replacement.text() + text.substring(replacement.end())).split("\n")[1]);
    }
}
