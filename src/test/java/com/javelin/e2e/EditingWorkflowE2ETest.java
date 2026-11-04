package com.javelin.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.editor.Brackets;
import com.javelin.editor.LineNavigation;
import com.javelin.editor.SyntaxHighlighter;
import com.javelin.editor.TextSearch;
import com.javelin.io.FileIO;
import com.javelin.language.Completions;
import com.javelin.language.Lsp;
import com.javelin.language.TextEdits;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Moving around and editing a real file, the way the window does it: open it, jump to a line, match
 * a bracket, find and replace, accept a completion, save, reopen. The window only applies what
 * these classes decide, so a failure here reads as "this editing feature no longer works".
 */
class EditingWorkflowE2ETest {
  @TempDir Path home;

  private static final String SOURCE =
      "class Greeter {\n    String greet(String name) {\n        return \"Hello, \" + name;\n    }\n}\n";

  private Path savedSource() throws IOException {
    Path file = home.resolve("Greeter.java");
    FileIO.writeAtomic(file, SOURCE);
    return file;
  }

  @Test
  void goToLineLandsOnTheTypedLineAndRejectsNonsense() throws IOException {
    String text = FileIO.readText(savedSource());
    int lines = text.split("\n", -1).length; // the editor counts the empty last line too
    assertEquals(6, lines);
    assertEquals(3, LineNavigation.parseLine(" 3 ", lines).orElseThrow());
    assertTrue(LineNavigation.parseLine("7", lines).isEmpty());
    LineNavigation.Position target =
        LineNavigation.clamp(3, 9, lines, line -> text.split("\n", -1)[line].length());
    assertEquals(new LineNavigation.Position(2, 8), target);
  }

  @Test
  void aProblemReportedPastTheEndOfAShortenedFileStillOpensSomewhereSensible() throws IOException {
    String text = FileIO.readText(savedSource());
    LineNavigation.Position target =
        LineNavigation.clamp(40, 200, 6, line -> text.split("\n", -1)[line].length());
    assertEquals(new LineNavigation.Position(5, 0), target, "the last (empty) line");
  }

  @Test
  void matchingABracketSelectsTheWholeBlock() throws IOException {
    String text = FileIO.readText(savedSource());
    int open = text.indexOf("{");
    Brackets.Pair pair = Brackets.pair(text, Brackets.near(text, open).orElseThrow()).orElseThrow();
    assertEquals(
        text.lastIndexOf("}") + 1, pair.end(), "the class body runs to the last closing brace");
    assertEquals(open, pair.start());
    assertEquals(
        open, Brackets.pair(text, pair.partner()).orElseThrow().partner(), "and back again");
  }

  @Test
  void matchingABracketIgnoresBracketsInStringsAndCommentsOfASavedFile() throws IOException {
    Path file = home.resolve("Parens.java");
    FileIO.writeAtomic(
        file,
        "class P {\n    boolean f(char c) {\n        // a stray ) in a comment\n        return c == ')' || g(\"(\") && h(c);\n    }\n}\n");
    String text = FileIO.readText(file);
    List<SyntaxHighlighter.Span> spans =
        SyntaxHighlighter.spans(
            text, file.toFile()); // the language comes from the extension, as in the window
    int open = text.indexOf("f(") + 1;
    Brackets.Pair pair =
        Brackets.pair(text, Brackets.near(text, open).orElseThrow(), spans).orElseThrow();
    assertEquals(
        text.indexOf("c) {") + 1,
        pair.partner(),
        "the parameter list ends at its own ), not at the one in the comment");
    int call = text.indexOf("g(") + 1;
    assertEquals(
        text.indexOf("\")", call) + 1,
        Brackets.pair(text, call, spans).orElseThrow().partner(),
        "the ( inside the string is not the call's");
    int body = text.indexOf("{", open);
    assertEquals(
        text.lastIndexOf("}", text.lastIndexOf("}") - 1),
        Brackets.pair(text, body, spans).orElseThrow().partner());
  }

  @Test
  void findNextWalksThroughMatchesAndReplaceSavesTheResult() throws IOException {
    Path file = savedSource();
    String text = FileIO.readText(file);
    TextSearch.Match first = TextSearch.first(text, "name", 0, true);
    TextSearch.Match second = TextSearch.next(text, "name", first.start(), first.end(), true);
    assertNotEquals(first, second);
    assertEquals(
        first,
        TextSearch.next(text, "name", second.start(), second.end(), true),
        "it wraps around");

    FileIO.writeAtomic(file, TextSearch.replace(text, "name", "who", true));
    assertEquals(SOURCE.replace("name", "who"), FileIO.readText(file));
  }

  @Test
  void acceptingACompletionReplacesThePrefixEvenIfTheUserKeptTyping() throws IOException {
    String text = FileIO.readText(savedSource());
    int caret =
        text.indexOf("(String name)")
            + "(String ".length()
            + 2; // the list is requested with "na|me" typed so far
    String prefixOnly =
        text.substring(0, caret)
            + text.substring(caret + 2); // the document as it was: "...(String na) {"
    Lsp.CompletionItem item = new Lsp.CompletionItem("name2", "String", 0, null, null, null, false);
    Completions.Plan plan =
        Completions.plan(
            prefixOnly, caret, List.of(item), position -> TextEdits.offset(prefixOnly, position));
    Completions.Candidate candidate = plan.candidates().get(0);
    assertEquals(caret - 2, candidate.start());

    String typedMore =
        prefixOnly.substring(0, caret)
            + "m"
            + prefixOnly.substring(caret); // one more character while the list was open
    Completions.Replacement replacement =
        Completions.replacement(candidate, caret, caret + 1, typedMore.length());
    String result =
        typedMore.substring(0, replacement.start())
            + replacement.text()
            + typedMore.substring(replacement.end());
    assertTrue(result.contains("greet(String name2)"), result);
  }

  @Test
  void aServerSuppliedEditIsAppliedExactlyWhereItSays() throws IOException {
    String text = FileIO.readText(savedSource());
    Lsp.Range range = new Lsp.Range(new Lsp.Position(1, 11), new Lsp.Position(1, 16)); // "greet"
    Lsp.CompletionItem item =
        new Lsp.CompletionItem(
            "greetAll", null, 0, null, null, new Lsp.TextEdit(range, "greetAll"), false);
    Completions.Plan plan =
        Completions.plan(
            text,
            text.indexOf("greet") + 5,
            List.of(item),
            position -> TextEdits.offset(text, position));
    Completions.Replacement replacement =
        Completions.replacement(
            plan.candidates().get(0),
            text.indexOf("greet") + 5,
            text.indexOf("greet") + 5,
            text.length());
    assertEquals(
        "    String greetAll(String name) {",
        (text.substring(0, replacement.start())
                + replacement.text()
                + text.substring(replacement.end()))
            .split("\n")[1]);
  }

  @Test
  void aShellScriptIsHighlightedAfterItIsSavedAndReopened() throws IOException {
    String script =
        "#!/bin/sh\nname=\"$1\"\nif [ -z \"$name\" ]; then\n  echo 'usage: run NAME' # help\n  exit 1\nfi\ncat <<EOF\nhello $name\nEOF\n";
    Path file = home.resolve("run.sh");
    FileIO.writeAtomic(file, script);
    String reopened = FileIO.readText(file);
    assertEquals(script, reopened);
    assertTrue(SyntaxHighlighter.appliesTo(file.toFile()));
    List<String> coloured =
        SyntaxHighlighter.spans(reopened, file.toFile()).stream()
            .map(s -> s.style() + ":" + reopened.substring(s.start(), s.end()))
            .toList();
    assertTrue(
        coloured.containsAll(
            List.of(
                "comment:#!/bin/sh",
                "type:name",
                "keyword:if",
                "keyword:then",
                "annotation:echo",
                "string:'usage: run NAME'",
                "comment:# help",
                "annotation:exit",
                "number:1",
                "keyword:fi",
                "keyword:EOF",
                "string:hello $name")),
        coloured.toString());
    assertTrue(
        SyntaxHighlighter.spans(reopened, home.resolve("run.txt").toFile()).isEmpty(),
        "the same text in a .txt file stays plain");
  }

  @Test
  void aScriptWithoutAnExtensionIsHighlightedFromItsFirstLineAndAStartUpFileByName()
      throws IOException {
    String script = "#!/usr/bin/env bash\nset -e\nif [ -n \"$HOME\" ]; then echo \"$HOME\"; fi\n";
    Path deploy = home.resolve("deploy");
    FileIO.writeAtomic(deploy, script);
    String reopened = FileIO.readText(deploy);
    List<String> coloured =
        SyntaxHighlighter.spans(reopened, deploy.toFile()).stream()
            .map(s -> s.style() + ":" + reopened.substring(s.start(), s.end()))
            .toList();
    assertTrue(
        coloured.containsAll(
            List.of("comment:#!/usr/bin/env bash", "keyword:if", "keyword:then", "keyword:fi")),
        coloured.toString());

    Path plain = home.resolve("notes");
    FileIO.writeAtomic(plain, "just words\n#!/bin/sh\n");
    assertTrue(
        SyntaxHighlighter.spans(FileIO.readText(plain), plain.toFile()).isEmpty(),
        "a #! line that is not the first line does not make a script");

    Path rc = home.resolve(".zshrc");
    FileIO.writeAtomic(rc, "export PATH=\"$HOME/bin:$PATH\" # mine\n");
    assertFalse(
        SyntaxHighlighter.spans(FileIO.readText(rc), rc.toFile()).isEmpty(),
        ".zshrc is shell without any extension or #! line");
  }
}
