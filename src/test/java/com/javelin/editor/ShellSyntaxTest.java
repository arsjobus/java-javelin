package com.javelin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What a shell script gets coloured, token by token. Each expectation reads {@code style:text}. */
class ShellSyntaxTest {
  private static List<String> styled(String script) {
    return SyntaxHighlighter.spans(script, new File("run.sh")).stream()
        .map(s -> s.style() + ":" + script.substring(s.start(), s.end()))
        .toList();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a.sh",
        "A.SH",
        "install.bash",
        "x.zsh",
        "x.ksh",
        "run.command",
        "dir.py/script.sh"
      })
  void shellFilesAreHighlighted(String name) {
    assertTrue(SyntaxHighlighter.appliesTo(new File(name)), name);
    assertEquals(SyntaxHighlighter.Language.SHELL, SyntaxHighlighter.languageOf(new File(name)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a.shx", "sh", "script.sh.bak", "run.csh.txt"})
  void similarNamesAreNot(String name) {
    assertFalse(SyntaxHighlighter.appliesTo(new File(name)), name);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".bashrc",
        ".BASHRC",
        ".bash_profile",
        ".bash_login",
        ".bash_logout",
        ".bash_aliases",
        ".profile",
        ".zshrc",
        ".zprofile",
        ".zshenv",
        ".zlogin",
        ".zlogout",
        ".kshrc",
        ".envrc",
        "home/me/.zshrc"
      })
  void shellStartUpFilesAreHighlightedByName(String name) {
    assertEquals(
        SyntaxHighlighter.Language.SHELL, SyntaxHighlighter.languageOf(new File(name)), name);
    assertTrue(SyntaxHighlighter.appliesTo(new File(name)), name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"bashrc", ".bashrc.bak", "my.zshrc", ".profile.d", ".fishrc", "profile"})
  void similarStartUpNamesAreNot(String name) {
    assertFalse(SyntaxHighlighter.appliesTo(new File(name)), name);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "#!/bin/sh\n",
        "#!/bin/bash\necho hi",
        "#!/usr/bin/env bash\n",
        "#!/usr/bin/env -S bash -e\n",
        "#!/usr/bin/env FOO=1 zsh\n",
        "#! /bin/zsh\n",
        "#!/bin/ksh",
        "#!/bin/dash\r\necho hi",
        "#!/usr/local/bin/BASH\n",
        "#!/bin/sh -e\n"
      })
  void anExtensionlessFileWithAShellShebangIsShell(String text) {
    File script = new File("deploy");
    assertEquals(
        SyntaxHighlighter.Language.SHELL, SyntaxHighlighter.languageOf(script, text), text);
    assertTrue(SyntaxHighlighter.appliesTo(script, text), text);
    assertFalse(
        SyntaxHighlighter.spans(text, script).isEmpty(),
        "it is coloured, not just recognised: " + text);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "echo hi\n",
        " #!/bin/sh\n",
        "# !/bin/sh\n",
        "#!\n",
        "#!/usr/bin/env\n",
        "#!/usr/bin/env -S\n",
        "#!/usr/bin/ruby\n",
        "#!/bin/csh\n",
        "\n#!/bin/sh\n",
        "#!/bin/bashful\n",
        "#!/usr/bin/pythonista\n"
      })
  void otherFirstLinesLeaveTheFileAsPlainText(String text) {
    assertNull(SyntaxHighlighter.languageOf(new File("deploy"), text), text);
    assertTrue(SyntaxHighlighter.spans(text, new File("deploy")).isEmpty(), text);
  }

  @Test
  void theShebangIsOnlyReadInTheFirstLine() {
    assertNull(SyntaxHighlighter.languageOf(new File("deploy"), "echo hi\n#!/bin/sh\n"));
    assertNull(
        SyntaxHighlighter.languageOf(new File("deploy"), "#!" + " ".repeat(300) + "/bin/sh\n"),
        "a first line that long is not an interpreter line");
  }

  @Test
  void aFileWithAnExtensionIsNeverSecondGuessed() {
    assertNull(SyntaxHighlighter.languageOf(new File("notes.txt"), "#!/bin/sh\n"));
    assertNull(SyntaxHighlighter.languageOf(new File("a.rb"), "#!/bin/bash\n"));
    assertEquals(
        SyntaxHighlighter.Language.PYTHON,
        SyntaxHighlighter.languageOf(new File("a.py"), "#!/bin/bash\n"),
        "the extension wins");
  }

  @Test
  void aHiddenFileOrATrailingDotHasNoExtensionSoItsFirstLineCounts() {
    assertEquals(
        SyntaxHighlighter.Language.SHELL,
        SyntaxHighlighter.languageOf(new File(".myscript"), "#!/bin/sh\n"));
    assertEquals(
        SyntaxHighlighter.Language.SHELL,
        SyntaxHighlighter.languageOf(new File("run."), "#!/bin/sh\n"));
  }

  @Test
  void anUntitledBufferIsStillJavaWhateverItsFirstLine() {
    assertEquals(
        SyntaxHighlighter.Language.JAVA, SyntaxHighlighter.languageOf(null, "#!/bin/sh\n"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "#!/usr/bin/python|PYTHON",
        "#!/usr/bin/env python3|PYTHON",
        "#!/usr/bin/env python3.12|PYTHON",
        "#!/usr/bin/env node|JAVASCRIPT",
        "#!/usr/bin/nodejs|JAVASCRIPT"
      })
  void pythonAndNodeScriptsAreRecognisedToo(String text, SyntaxHighlighter.Language expected) {
    assertEquals(expected, SyntaxHighlighter.languageOf(new File("tool"), text), text);
  }

  @Test
  void theColoursOfAScriptWithoutAnExtensionAreThoseOfAShFile() {
    String script = "#!/bin/bash\nif [ -f x ]; then echo \"$HOME\"; fi\n";
    assertEquals(
        SyntaxHighlighter.spans(script, new File("run.sh")),
        SyntaxHighlighter.spans(script, new File("run")));
  }

  @Test
  void commentsStartAtTheStartOfAWord() {
    assertEquals(
        List.of(
            "comment:#!/bin/sh",
            "annotation:echo",
            "comment:# trailing",
            "annotation:echo",
            "type:$#"),
        styled("#!/bin/sh\necho hi # trailing\necho a#b $#"));
  }

  @Test
  void singleAndDoubleQuotedStringsHideTheirContents() {
    assertEquals(
        List.of("annotation:echo", "string:'if # $x \"'", "string:\"it's # $x\""),
        styled("echo 'if # $x \"' \"it's # $x\""));
  }

  @Test
  void aCommandSubstitutionInsideDoubleQuotesMayHoldQuotes() {
    assertEquals(
        List.of("annotation:echo", "string:\"a $(echo \"b c\" 'd\"') e\"", "number:7"),
        styled("echo \"a $(echo \"b c\" 'd\"') e\" 7"));
  }

  @Test
  void quotedStringsMaySpanLines() {
    assertEquals(
        List.of("annotation:echo", "string:\"a\nb\"", "annotation:echo", "string:'c\nd'"),
        styled("echo \"a\nb\" c\necho 'c\nd'"));
  }

  @Test
  void ansiCAndLocaleQuotes() {
    assertEquals(
        List.of("annotation:echo", "string:$'a\\'b\\tc'", "string:$\"x y\""),
        styled("echo $'a\\'b\\tc' $\"x y\""));
  }

  @Test
  void variablesAndSpecialParameters() {
    assertEquals(
        List.of(
            "annotation:echo",
            "type:$HOME",
            "type:${PATH:-x}",
            "type:$1",
            "type:$@",
            "type:$?",
            "type:$$",
            "string:\"$x\""),
        styled("echo $HOME ${PATH:-x} $1 $@ $? $$ \"$x\""));
  }

  @Test
  void aLoneDollarAndAnUnclosedBraceStayPlain() {
    assertEquals(List.of("annotation:echo"), styled("echo $ ${unclosed\n"));
    assertEquals(List.of("annotation:echo", "type:$a"), styled("echo $ $a"));
  }

  @Test
  void reservedWordsAndBuiltinsInCommandPosition() {
    assertEquals(
        List.of(
            "keyword:if",
            "annotation:[",
            "keyword:then",
            "annotation:echo",
            "keyword:elif",
            "annotation:true",
            "keyword:then",
            "annotation::",
            "keyword:else",
            "annotation:exit",
            "number:1",
            "keyword:fi"),
        styled("if [ -f x ]; then echo yes; elif true; then :; else exit 1; fi"));
  }

  @Test
  void reservedWordsAndBuiltinsAreWordsNotKeywordsWhenTheyAreArguments() {
    assertEquals(List.of("annotation:echo"), styled("echo if then done cd"));
    assertEquals(List.of(), styled("git cd done"));
    assertEquals(List.of("annotation:echo", "annotation:echo"), styled("echo a \\\n  if\necho b"));
  }

  @Test
  void loopsAndCase() {
    assertEquals(
        List.of(
            "keyword:for",
            "keyword:in",
            "number:1",
            "number:2",
            "keyword:do",
            "type:$f",
            "keyword:done"),
        styled("for f in 1 2; do cp $f x; done"));
    assertEquals(
        List.of(
            "keyword:while",
            "annotation:read",
            "keyword:do",
            "annotation:echo",
            "string:\"$line\"",
            "keyword:done"),
        styled("while read line; do echo \"$line\"; done < file"));
    assertEquals(
        List.of(
            "keyword:case",
            "string:\"$x\"",
            "keyword:in",
            "annotation:echo",
            "annotation:echo",
            "keyword:esac"),
        styled("case \"$x\" in\n a) echo a;;\n b|c) echo b;;\nesac"));
  }

  @Test
  void inIsReservedOnlyAfterForCaseOrSelect() {
    assertEquals(
        List.of("annotation:echo", "keyword:for", "keyword:in"), styled("echo in\nfor x in"));
  }

  @Test
  void doubleBracketsAreKeywords() {
    assertEquals(
        List.of("keyword:[[", "type:$x", "keyword:]]", "annotation:echo"),
        styled("[[ -n $x ]] && echo y"));
  }

  @Test
  void assignmentsColourTheNameAndKeepCommandPosition() {
    assertEquals(
        List.of("type:FOO", "type:BAZ", "string:\"x y\"", "annotation:echo"),
        styled("FOO=bar BAZ=\"x y\" echo hi"));
    assertEquals(
        List.of("annotation:export", "type:PATH", "type:$PATH"), styled("export PATH=$PATH:/x"));
    assertEquals(List.of("type:n"), styled("n+=1"));
    assertEquals(
        List.of("type:x", "annotation:cd", "annotation:pwd"), styled("x=$(cd /tmp && pwd)"));
  }

  @Test
  void commandSubstitutionSubshellsAndBackticksStartACommand() {
    assertEquals(List.of("annotation:echo", "annotation:pwd"), styled("echo $(pwd) x"));
    assertEquals(List.of("annotation:echo", "annotation:pwd"), styled("echo `pwd` x"));
    assertEquals(List.of("annotation:cd", "annotation:exit"), styled("(cd x; exit)"));
    assertEquals(
        List.of("annotation:true", "annotation:exit", "number:2", "annotation:echo"),
        styled("true && exit 2 || echo x"));
  }

  @Test
  void functionBodiesStartACommand() {
    assertEquals(
        List.of("keyword:function", "annotation:echo"), styled("function greet { echo hi; }"));
    assertEquals(List.of("annotation:echo"), styled("greet() { echo hi; }"));
  }

  @Test
  void arithmeticIsNotAHeredoc() {
    assertEquals(
        List.of("annotation:echo", "number:1", "number:3", "annotation:echo"),
        styled("echo $((1<<3))\necho x"));
    assertEquals(List.of("type:i"), styled("i=$((i+1))"));
  }

  @Test
  void hereDocumentBodiesAreStringsAndTheDelimiterIsAKeyword() {
    assertEquals(
        List.of("keyword:EOF", "string:it's # $x", "keyword:EOF", "annotation:echo"),
        styled("cat <<EOF\nit's # $x\nEOF\necho done"));
  }

  @Test
  void hereDocumentVariants() {
    assertEquals(
        List.of("keyword:END", "string:\tx", "keyword:END", "annotation:echo"),
        styled("cat <<-END\n\tx\n\tEND\necho y"));
    assertEquals(
        List.of("keyword:\"END\"", "string:$x", "keyword:END"), styled("cat <<\"END\"\n$x\nEND\n"));
    assertEquals(
        List.of("keyword:A", "keyword:B", "string:1", "keyword:A", "string:2", "keyword:B"),
        styled("cat <<A <<B\n1\nA\n2\nB\n"));
    assertEquals(
        List.of("keyword:EOF", "string:body", "string:more"), styled("cat <<EOF\nbody\nmore"));
    assertEquals(
        List.of("keyword:EOF", "string:a", "keyword:EOF"), styled("cat <<EOF\r\na\r\nEOF\r\n"));
  }

  @Test
  void aLineThatOnlyStartsWithTheDelimiterDoesNotEndTheHereDocument() {
    assertEquals(
        List.of("keyword:EOF", "string:EOF2", "string:  EOF", "keyword:EOF"),
        styled("cat <<EOF\nEOF2\n  EOF\nEOF"));
  }

  @Test
  void hereStringsAndNumbersAfterAnglesAreNotHereDocuments() {
    assertEquals(List.of("string:\"x y\"", "annotation:echo"), styled("cat <<< \"x y\"\necho z"));
    assertEquals(List.of("number:1", "annotation:echo"), styled("x <<1\necho y"));
    assertEquals(
        List.of("annotation:echo", "string:\"\""),
        styled("echo <<\"\"\n")); // an empty delimiter is no delimiter
  }

  @Test
  void redirectionsDoNotStartACommand() {
    assertEquals(List.of("annotation:echo", "number:2", "number:1"), styled("echo x 2>&1 cd"));
    assertEquals(List.of("annotation:echo"), styled("echo x &>out cd"));
    assertEquals(List.of("annotation:echo", "annotation:echo"), styled("echo x & echo y"));
  }

  @Test
  void aQuoteWithNoPartnerColoursOnlyItsLine() {
    assertEquals(
        List.of("annotation:echo", "string:\"oops", "annotation:echo"),
        styled("echo \"oops\necho ok"));
    assertEquals(
        List.of("annotation:echo", "string:'s", "annotation:echo"), styled("echo it's\necho ok"));
    assertEquals(
        List.of("annotation:echo", "string:\"a $(b", "annotation:echo"),
        styled("echo \"a $(b\necho x"));
    assertEquals(
        List.of("annotation:echo", "string:$'x", "annotation:echo"), styled("echo $'x\necho y"));
  }

  @Test
  void windowsLineEndingsStayOutOfTheTokens() {
    assertEquals(
        List.of("comment:# c", "annotation:echo", "string:'a'", "keyword:fi"),
        styled("# c\r\necho 'a'\r\nfi\r\n").stream().toList());
  }

  @Test
  void spansDoNotOverlapAndStayInOrder() {
    String script =
        "#!/bin/bash\nset -e\nfor f in *.txt; do\n  name=${f%.txt}\n  cat <<EOF >\"$name.out\"\nfile: $f 'x'\nEOF\ndone\necho \"$(date +'%F')\" 'it' $((1<<2)) `pwd`\n";
    int end = 0;
    for (SyntaxHighlighter.Span span :
        SyntaxHighlighter.spansIn(script, SyntaxHighlighter.Language.SHELL)) {
      assertTrue(span.start() >= end, "overlap at " + span);
      assertTrue(span.end() > span.start() && span.end() <= script.length(), "bad range " + span);
      end = span.end();
    }
  }

  @Test
  void aRealisticScriptIsColouredAsAReaderWouldExpect() {
    String script =
        "#!/usr/bin/env bash\nset -euo pipefail\nname=\"${1:-world}\"\nif [ -z \"$name\" ]; then\n  exit 1\nfi\necho \"Hello, $name\" # greet\n";
    assertEquals(
        List.of(
            "comment:#!/usr/bin/env bash",
            "annotation:set",
            "type:name",
            "string:\"${1:-world}\"",
            "keyword:if",
            "annotation:[",
            "string:\"$name\"",
            "keyword:then",
            "annotation:exit",
            "number:1",
            "keyword:fi",
            "annotation:echo",
            "string:\"Hello, $name\"",
            "comment:# greet"),
        styled(script));
  }

  @Test
  void longLinesOfStrayQuotesStayFast() {
    for (String piece :
        new String[] {"\"x ", "'x ", "$(", "${", "<<\"", "<<'", "\"$( ", "$'", "`", "\"$('"}) {
      String line = piece.repeat(100_000) + "\n";
      long start = System.nanoTime();
      SyntaxHighlighter.spansIn(line.repeat(5), SyntaxHighlighter.Language.SHELL);
      assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "slow for " + piece);
    }
    String lines = "echo \"a\n".repeat(100_000);
    long start = System.nanoTime();
    SyntaxHighlighter.spansIn(lines, SyntaxHighlighter.Language.SHELL);
    assertTrue(
        (System.nanoTime() - start) / 1_000_000 < 5_000,
        "slow for many lines with one stray quote each");
  }

  @Test
  void hugeFilesAreNotHighlighted() {
    assertTrue(
        SyntaxHighlighter.spans(
                "echo x\n".repeat(SyntaxHighlighter.MAX_CHARS / 4), new File("a.sh"))
            .isEmpty());
  }
}
