package com.javelin_ide.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.editor.SyntaxHighlighter.Language;
import com.javelin_ide.editor.SyntaxHighlighter.Span;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SyntaxHighlighterTest {
    private static List<String> styled(String text) {
        return SyntaxHighlighter.spans(text).stream().map(s -> s.style() + ":" + text.substring(s.start(), s.end())).toList();
    }

    private static List<String> styled(String text, String fileName) {
        return SyntaxHighlighter.spans(text, new File(fileName)).stream().map(s -> s.style() + ":" + text.substring(s.start(), s.end())).toList();
    }

    @Test void recognisesEveryTokenKind() {
        assertEquals(List.of("keyword:public", "keyword:class", "annotation:@Override", "type:String", "number:42", "number:3.14", "comment:// hi", "string:\"s\"", "string:'c'", "comment:/* b */"),
            styled("public class @Override String 42 3.14 // hi\n\"s\" 'c' /* b */"));
    }

    @Test void identifiersThatContainKeywordsAreNotHighlighted() {
        assertEquals(List.of(), styled("classify interface_ publicity new1 integer Strings x1 1x"));
        assertEquals(List.of("keyword:new"), styled("x.new y"));
    }

    @Test void stringsAndCommentsHideTheirContents() {
        assertEquals(List.of("string:\"if (class) // not a comment\""), styled("\"if (class) // not a comment\""));
        assertEquals(List.of("comment:// \"quote\" class"), styled("// \"quote\" class"));
        assertEquals(List.of("comment:/* class \" */", "keyword:int".replace("keyword", "type")), styled("/* class \" */ int"));
    }

    @Test void escapesInsideStrings() {
        assertEquals(List.of("string:\"a\\\"b\"", "number:1"), styled("\"a\\\"b\" 1"));
        assertEquals(List.of("string:'\\''"), styled("'\\''"));
    }

    @Test void aStrayQuoteCannotColourTheRestOfTheFile() {
        assertEquals(List.of("keyword:class"), styled("it's broken\nclass".replace("it's broken\n", "\"unterminated\n")));
        assertEquals(List.of("type:int", "number:7"), styled("don't\nint 7".replace("don't", "don'").replace("don'\n", "don'\n")) .stream().filter(s -> !s.startsWith("string")).toList());
        List<String> afterApostrophe = styled("// it's fine\nint x = 'a';");
        assertEquals(List.of("comment:// it's fine", "type:int", "string:'a'"), afterApostrophe);
    }

    @Test void anUnterminatedBlockCommentRunsToTheEnd() {
        assertEquals(List.of("keyword:int".replace("keyword", "type"), "comment:/* never closed\nclass A {"), styled("int /* never closed\nclass A {"));
        assertEquals(List.of("comment:/*"), styled("/*"));
    }

    @Test void blockCommentsSpanLines() { assertEquals(List.of("comment:/* a\nb\nc */"), styled("/* a\nb\nc */")); }

    @Test void textBlocksSpanLines() {
        assertEquals(List.of("string:\"\"\"\n  class \"quoted\" \n  \"\"\"", "number:1"), styled("\"\"\"\n  class \"quoted\" \n  \"\"\" 1"));
        assertEquals(List.of("string:\"\"\"\nopen"), styled("\"\"\"\nopen"));
    }

    @Test void emptyAndPlainTextHaveNoSpans() {
        assertTrue(SyntaxHighlighter.spans("").isEmpty());
        assertTrue(SyntaxHighlighter.spans("just some prose, nothing to see").isEmpty());
    }

    @Test void spansNeverOverlapAndStayInOrder() {
        String code = "@Deprecated public static void main(String[] a) { int x = 10; // c\n String s = \"q\"; /* m */ return; }\n".repeat(50);
        int end = 0;
        for (Span s : SyntaxHighlighter.spans(code)) { assertTrue(s.start() >= end); assertTrue(s.end() > s.start()); end = s.end(); }
        assertTrue(end <= code.length());
    }

    @Test void hugeDocumentsAreNotHighlightedAtAll() {
        assertFalse(SyntaxHighlighter.tooLarge(SyntaxHighlighter.MAX_CHARS));
        assertTrue(SyntaxHighlighter.tooLarge(SyntaxHighlighter.MAX_CHARS + 1));
        String big = "int x = 1; ".repeat(SyntaxHighlighter.MAX_CHARS / 10);
        assertTrue(big.length() > SyntaxHighlighter.MAX_CHARS);
        assertTrue(SyntaxHighlighter.spans(big).isEmpty());
    }

    @Test void aMegabyteOfCodeHighlightsWithinABudget() {
        String code = "public static int compute(String name) { return name.length() + 42; } // trailing \"comment\"\n";
        String text = code.repeat(SyntaxHighlighter.MAX_CHARS / code.length());
        long start = System.nanoTime();
        List<Span> spans = SyntaxHighlighter.spans(text);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertFalse(spans.isEmpty());
        assertTrue(ms < 3_000, "highlighting 1 MB took " + ms + " ms");
    }

    @Test void pathologicalInputStaysLinear() {
        String[] nasty = {"/*".repeat(400_000), "\"".repeat(800_000), "'a".repeat(400_000), "\"\"\"".repeat(250_000), "/".repeat(800_000), "\\\"".repeat(400_000), "@".repeat(800_000), "\"" + "\\".repeat(800_000)};
        for (String text : nasty) {
            long start = System.nanoTime();
            SyntaxHighlighter.spans(text);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 3_000, "pathological input took " + ms + " ms: " + text.substring(0, 4));
        }
    }

    @Test void manyUnterminatedStringsOnOneLineStayFast() {
        String line = "\"x ".repeat(100_000) + "\n";
        long start = System.nanoTime();
        SyntaxHighlighter.spans(line.repeat(5));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A.java", "a.JAVA", "a.kt", "a.kts", "a.js", "a.mjs", "a.cjs", "a.jsx", "a.ts", "a.tsx", "a.c", "a.h", "a.cpp", "a.cc", "a.cxx", "a.hpp", "a.hh",
        "a.go", "a.rs", "a.php", "a.json", "a.md", "a.markdown", "a.yml", "a.yaml", "archive.tar.java", "dir.py/Main.java"})
    void cFamilyFilesAreHighlighted(String name) { assertTrue(SyntaxHighlighter.appliesTo(new File(name)), name); }

    @ParameterizedTest
    @ValueSource(strings = {"a.rb", "a.sh", "a.bash", "a.txt", "a.toml", "a.properties", "README", "Makefile", ".bashrc", "trailing.", "a.java.bak", "dir.java/notes"})
    void otherFilesAreShownAsPlainText(String name) { assertFalse(SyntaxHighlighter.appliesTo(new File(name)), name); }

    @Test void anUntitledBufferIsHighlighted() { assertTrue(SyntaxHighlighter.appliesTo(null)); }

    @Test void proseInAPlainTextFileIsNeverColoured() {
        String prose = "don't panic: it's \"fine\" // not a comment\nclass of 42 things /* nor this */";
        assertTrue(SyntaxHighlighter.spans(prose, new File("notes.txt")).isEmpty());
        assertTrue(SyntaxHighlighter.spans(prose, new File("README")).isEmpty());
        assertFalse(SyntaxHighlighter.spans(prose, new File("Notes.java")).isEmpty());
    }

    @Test void pythonCommentsAndApostrophesAreScannedAsPythonNotJava() {
        String python = "# it's a comment\nclass Foo:\n    x = 'don\\'t'  # 42\n";
        assertEquals(List.of("comment:# it's a comment", "keyword:class", "string:'don\\'t'", "comment:# 42"), styled(python, "main.py"));
    }

    @Test void spansWithAFileMatchesPlainSpansWhenTheFileIsHighlighted() {
        String code = "public class A { int x = 1; // c\n }";
        assertEquals(SyntaxHighlighter.spans(code), SyntaxHighlighter.spans(code, new File("A.java")));
        assertEquals(SyntaxHighlighter.spans(code), SyntaxHighlighter.spans(code, null));
        assertTrue(SyntaxHighlighter.spans("", new File("a.py")).isEmpty());
        assertTrue(SyntaxHighlighter.spans("def x", new File("a.rb")).isEmpty());
    }

    @Test void theResultForAnUnhighlightedFileIsIndependentOfTheScanner() {
        List<Span> a = SyntaxHighlighter.spans("class A", new File("a.txt"));
        a.add(new Span(0, 1, "x")); // callers may mutate the returned list without affecting later calls
        assertTrue(SyntaxHighlighter.spans("class A", new File("a.txt")).isEmpty());
    }

    // --- keywords belong to the language of the file (BUGS.md: "keyword colours are Java's for every highlighted language") ---

    @ParameterizedTest
    @CsvSource({"A.java,JAVA", "a.JAVA,JAVA", "a.kt,KOTLIN", "a.kts,KOTLIN", "a.js,JAVASCRIPT", "a.mjs,JAVASCRIPT", "a.cjs,JAVASCRIPT", "a.jsx,JAVASCRIPT",
        "a.ts,TYPESCRIPT", "a.tsx,TYPESCRIPT", "a.c,C", "a.h,CPP", "a.cpp,CPP", "a.cc,CPP", "a.cxx,CPP", "a.hpp,CPP", "a.hh,CPP", "a.go,GO", "a.rs,RUST",
        "a.php,PHP", "a.json,JSON", "a.md,MARKDOWN", "a.markdown,MARKDOWN", "a.yml,YAML", "a.YAML,YAML", "dir.py/Main.java,JAVA"})
    void eachExtensionMapsToItsLanguage(String name, Language expected) { assertEquals(expected, SyntaxHighlighter.languageOf(new File(name)), name); }

    @ParameterizedTest
    @CsvSource({"a.rb", "a.txt", "README", "Makefile", ".bashrc", "trailing.", "a.java.bak", "dir.java/notes"})
    void otherFilesHaveNoLanguage(String name) { assertNull(SyntaxHighlighter.languageOf(new File(name)), name); }

    @Test void anUntitledBufferIsJava() { assertEquals(Language.JAVA, SyntaxHighlighter.languageOf(null)); }

    @Test void appliesToAgreesWithLanguageOf() {
        for (String name : List.of("A.java", "a.rs", "a.json", "a.py", "a.md", "README", "a.txt")) {
            File file = new File(name);
            assertEquals(SyntaxHighlighter.languageOf(file) != null, SyntaxHighlighter.appliesTo(file), name);
        }
        assertTrue(SyntaxHighlighter.appliesTo(null));
    }

    @Test void everyLanguageHasAKeywordList() {
        for (Language language : Language.values()) assertDoesNotThrow(() -> SyntaxHighlighter.spansIn("x 1 \"s\" // c", language), language.name());
    }

    @Test void rustKeywordsAndTypesAreHighlightedInRustFiles() {
        assertEquals(List.of("keyword:fn", "keyword:let", "keyword:mut", "type:u32", "number:1", "keyword:match", "keyword:Self"),
            styled("fn main() { let mut x: u32 = 1; match Self }", "main.rs"));
    }

    @Test void rustLifetimesAreNotMistakenForCharacterLiterals() {
        // Used to colour 'a>(x: &' as one string and hide everything inside it.
        assertEquals(List.of("keyword:fn", "type:str", "type:str"), styled("fn first<'a>(x: &'a str) -> &'a str { x }", "lib.rs"));
        assertEquals(List.of("keyword:impl", "keyword:fn", "keyword:self", "type:i32", "number:1"),
            styled("impl<'a> Foo<'a> { fn f(&self) -> i32 { 1 } }", "lib.rs"));
        assertEquals(List.of("keyword:static", "type:str"), styled("static S: &'static str", "lib.rs"));
    }

    @Test void rustLoopLabelsDoNotHideTheLoop() {
        assertEquals(List.of("keyword:loop", "keyword:break"), styled("'outer: loop { break 'outer; }", "main.rs"));
        assertEquals(List.of("keyword:for", "keyword:in", "keyword:continue"), styled("'rows: for x in xs { continue 'rows }", "main.rs"));
    }

    @Test void rustCharacterLiteralsAreStillStrings() {
        assertEquals(List.of("keyword:let", "string:'x'", "keyword:let", "string:'\\n'", "keyword:let", "string:'\u00e9'"),
            styled("let c = 'x'; let d = '\\n'; let e = '\u00e9';", "main.rs"));
        // A lifetime and a one-letter character literal side by side: only the literal is a string.
        assertEquals(List.of("keyword:fn", "type:str", "keyword:let", "string:'a'"), styled("fn f<'a>(s: &'a str) { let x = 'a'; }", "main.rs"));
    }

    // Rust attributes (BUGS.md: "no Rust `#[...]` attributes")
    @Test void rustOuterAttributesAreAnnotations() {
        assertEquals(List.of("annotation:#[derive", "keyword:struct"), styled("#[derive(Debug)] struct", "a.rs"));
        assertEquals(List.of("annotation:#[test", "keyword:fn"), styled("#[test]\nfn t() {}", "a.rs"));
    }

    @Test void rustInnerAttributesAreAnnotations() {
        assertEquals(List.of("annotation:#![allow"), styled("#![allow(dead_code)]", "a.rs"));
        assertEquals(List.of("annotation:#![no_std"), styled("#![no_std]", "a.rs"));
    }

    @Test void rustAttributePathsAreOneAnnotation() {
        assertEquals(List.of("annotation:#[tokio::main", "keyword:async", "keyword:fn"), styled("#[tokio::main]\nasync fn main() {}", "a.rs"));
        assertEquals(List.of("annotation:#[cfg_attr", "annotation:#[serde::rename"), styled("#[cfg_attr(a, b)] #[serde::rename = x]", "a.rs"));
    }

    @Test void rustAttributeArgumentsAreStillScannedAsCode() {
        assertEquals(List.of("annotation:#[doc", "string:\"fn\""), styled("#[doc = \"fn\"]", "a.rs"));
        assertEquals(List.of("annotation:#[cfg", "annotation:#[test"), styled("#[cfg(test)] #[test]", "a.rs"));
    }

    @Test void aHashThatIsNotAnAttributeStaysPlainInRust() {
        assertEquals(List.of(), styled("# [", "a.rs"));
        assertEquals(List.of(), styled("#[", "a.rs"));
        assertEquals(List.of(), styled("#![", "a.rs"));
        assertEquals(List.of("number:1"), styled("#[1]", "a.rs")); // no name after the bracket: not an attribute
        assertEquals(List.of(), styled("#", "a.rs"));
        assertTrue(styled("r#\"x\"#", "a.rs").stream().noneMatch(x -> x.startsWith("annotation")));
    }

    @Test void rustAttributesInStringsAndCommentsStayHidden() {
        assertEquals(List.of("string:\"#[derive]\"", "comment:// #[test]"), styled("\"#[derive]\" // #[test]", "a.rs"));
        assertEquals(List.of("comment:/* #![x] */"), styled("/* #![x] */", "a.rs"));
    }

    @Test void onlyRustTreatsHashBracketAsAnAttribute() {
        for (String name : List.of("a.c", "a.cpp", "a.go", "a.java", "a.kt", "a.ts", "a.js")) {
            assertTrue(styled("#[derive(Debug)]", name).stream().noneMatch(x -> x.startsWith("annotation:#")), name);
        }
        assertEquals(List.of("comment:#[derive(Debug)]"), styled("#[derive(Debug)]", "a.py")); // a Python comment
    }

    @Test void rustAttributesKeepTheTextScanLinear() {
        String text = "#[a::".repeat(20_000) + "#![".repeat(20_000);
        long start = System.nanoTime();
        List<Span> spans = SyntaxHighlighter.spans(text, new File("a.rs"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000);
        for (int i = 1; i < spans.size(); i++) assertTrue(spans.get(i - 1).end() <= spans.get(i).start());
    }

    @Test void aRustLifetimeAtTheEndOfTheTextIsPlain() {
        assertEquals(List.of(), styled("&'a", "main.rs"));
        assertEquals(List.of(), styled("&'", "main.rs"));
        assertEquals(List.of("keyword:fn"), styled("fn 'static", "main.rs"));
    }

    @Test void onlyRustTreatsAQuoteAndANameAsALifetime() {
        assertEquals(List.of("string:'a>(x: &'"), styled("'a>(x: &'", "A.java"));
        assertEquals(List.of("string:'a>(x: &'"), styled("'a>(x: &'", "a.c"));
    }

    @Test void goKeywordsAndTypesAreHighlightedInGoFiles() {
        assertEquals(List.of("keyword:func", "keyword:var", "type:string", "keyword:go", "keyword:defer", "keyword:nil"),
            styled("func main() { var s string; go run(); defer done(); nil }", "main.go"));
    }

    @Test void kotlinKeywordsAndTypesAreHighlightedInKotlinFiles() {
        assertEquals(List.of("keyword:fun", "keyword:val", "type:Int", "number:1", "keyword:when"), styled("fun main() { val x: Int = 1; when }", "Main.kt"));
        assertEquals(List.of("keyword:val"), styled("val", "build.gradle.kts"));
    }

    @Test void typeScriptKnowsInterfacesAndPrimitiveTypesButJavaScriptDoesNot() {
        String code = "interface Foo { x: number; } type T = string;";
        assertEquals(List.of("keyword:interface", "type:number", "keyword:type", "type:string"), styled(code, "a.ts"));
        assertEquals(List.of("keyword:interface", "type:number", "keyword:type", "type:string"), styled(code, "a.tsx"));
        assertEquals(List.of(), styled(code, "a.js"));
    }

    @Test void javaScriptKeywordsAreHighlightedInJavaScriptFiles() {
        assertEquals(List.of("keyword:const", "keyword:function", "keyword:await", "keyword:typeof", "keyword:undefined", "type:Promise"),
            styled("const function await typeof undefined Promise", "a.js"));
    }

    @Test void cAndCppDifferInTheirKeywords() {
        assertEquals(List.of("keyword:struct", "type:int", "keyword:typedef"), styled("struct int typedef class public", "a.c"));
        assertEquals(List.of("keyword:class", "keyword:public", "keyword:namespace", "type:string"), styled("class public namespace string", "a.cpp"));
        assertEquals(List.of("keyword:class"), styled("class", "a.h"));
    }

    @Test void phpKeywordsAreHighlightedInPhpFiles() {
        assertEquals(List.of("keyword:function", "keyword:echo", "keyword:foreach", "type:string"), styled("function echo foreach string $class", "a.php"));
    }

    @Test void javaKeywordsAreNotHighlightedInOtherLanguages() {
        assertEquals(List.of("keyword:class", "keyword:public", "keyword:void", "keyword:new", "keyword:implements", "keyword:synchronized"),
            styled("class public void new implements synchronized", "A.java"));
        String javaOnly = "class public void new implements synchronized";
        assertEquals(List.of(), styled(javaOnly, "a.go"));
        assertEquals(List.of(), styled(javaOnly, "a.rs"));
        assertEquals(List.of(), styled(javaOnly, "a.json"));
    }

    @Test void jsonOnlyHighlightsItsThreeLiterals() {
        assertEquals(List.of("string:\"class\"", "keyword:true", "string:\"n\"", "keyword:null", "string:\"v\"", "number:1", "string:\"x\"", "keyword:false"),
            styled("{\"class\": true, \"n\": null, \"v\": 1, \"x\": false}", "a.json"));
    }

    @Test void identifiersThatContainAKeywordAreStillNotHighlighted() {
        assertEquals(List.of(), styled("fnord letter mutable implement", "a.rs"));
        assertEquals(List.of(), styled("funcs gone variable", "a.go"));
    }

    @Test void annotationsAreOnlyHighlightedWhereTheLanguageHasThem() {
        assertEquals(List.of("annotation:@Override"), styled("@Override", "A.java"));
        assertEquals(List.of("annotation:@Component"), styled("@Component", "a.ts"));
        assertEquals(List.of("annotation:@JvmStatic"), styled("@JvmStatic", "a.kt"));
        for (String name : List.of("a.c", "a.cpp", "a.go", "a.rs", "a.php", "a.json")) assertEquals(List.of(), styled("@Override @foo", name), name);
    }

    @Test void stringsAndCommentsStillHideKeywordsInEveryLanguage() {
        assertEquals(List.of("string:\"fn\"", "comment:// let mut"), styled("\"fn\" // let mut", "a.rs"));
        assertEquals(List.of("comment:/* func var */", "keyword:go"), styled("/* func var */ go", "a.go"));
    }

    @Test void plainSpansAreJavaSpans() {
        String code = "fn class val func 1";
        assertEquals(List.of("keyword:class", "number:1"), styled(code));
        assertEquals(SyntaxHighlighter.spansIn(code, Language.JAVA), SyntaxHighlighter.spans(code));
        assertEquals(SyntaxHighlighter.spans(code), SyntaxHighlighter.spans(code, null));
    }

    @Test void spansForAFileUseTheLanguageOfThatFile() {
        String code = "fn class func val";
        assertEquals(SyntaxHighlighter.spansIn(code, Language.RUST), SyntaxHighlighter.spans(code, new File("a.rs")));
        assertEquals(SyntaxHighlighter.spansIn(code, Language.GO), SyntaxHighlighter.spans(code, new File("a.go")));
        assertNotEquals(SyntaxHighlighter.spans(code, new File("a.rs")), SyntaxHighlighter.spans(code, new File("a.go")));
    }

    @Test void everyLanguageStaysWithinTheSizeLimit() {
        String big = "x ".repeat(SyntaxHighlighter.MAX_CHARS);
        for (Language language : Language.values()) assertTrue(SyntaxHighlighter.spansIn(big, language).isEmpty(), language.name());
    }

    // --- Python, HTML and XML (BUGS.md: "Syntax highlighting understands C-family syntax only") ---

    @ParameterizedTest
    @ValueSource(strings = {"a.py", "a.PY", "a.pyi", "a.pyw", "a.html", "a.HTM", "a.htm", "a.xhtml", "a.xml", "a.xsd", "a.xsl", "a.xslt", "a.svg", "a.fxml", "a.plist",
        "a.rss", "a.atom", "a.wsdl", "pom.xml", "dir.txt/main.py"})
    void pythonHtmlAndXmlFilesAreHighlighted(String name) { assertTrue(SyntaxHighlighter.appliesTo(new File(name)), name); }

    @ParameterizedTest
    @ValueSource(strings = {"a.py.bak", "a.html.txt", "a.xml~", "python", "html", "xml", "dir.py/notes", "a.pyc", "a.htmlx"})
    void lookalikeFilesAreStillPlainText(String name) { assertFalse(SyntaxHighlighter.appliesTo(new File(name)), name); }

    @ParameterizedTest
    @CsvSource({"a.py,PYTHON", "a.PY,PYTHON", "a.pyi,PYTHON", "a.pyw,PYTHON", "a.html,HTML", "a.HTM,HTML", "a.htm,HTML", "a.xhtml,HTML", "a.xml,XML", "pom.xml,XML",
        "a.xsd,XML", "a.xsl,XML", "a.xslt,XML", "a.svg,XML", "a.fxml,XML", "a.plist,XML", "a.rss,XML", "a.atom,XML", "a.wsdl,XML"})
    void pythonHtmlAndXmlExtensionsMapToTheirLanguage(String name, Language expected) { assertEquals(expected, SyntaxHighlighter.languageOf(new File(name)), name); }

    @Test void markdownFilesHighlightBlockAndInlineFormatting() {
        String markdown = "# Heading\n\n- **bold** and *italic* with `code` and [link](https://example.test)\n> quoted\n```java\nclass Example {}\n```\n";
        assertEquals(List.of(
            "annotation:# ", "keyword:Heading",
            "keyword:- ", "annotation:**", "type:bold", "annotation:**", "annotation:*", "type:italic", "annotation:*",
            "string:`code`", "annotation:[", "type:link", "annotation:](", "string:https://example.test", "annotation:)",
            "comment:> ", "annotation:```java", "string:class Example {}", "annotation:```"),
            styled(markdown, "README.md"));
    }

    @Test void markdownLookalikesAndUnclosedInlineMarkersStayPlain() {
        assertEquals(List.of(), styled("ordinary prose with *unclosed emphasis and [link", "notes.md"));
        assertEquals(List.of("annotation:# ", "keyword:title"), styled("# title", "notes.markdown"));
        assertEquals(List.of(), styled("#not a heading", "notes.md"));
    }

    @Test void filesOfTheNewLanguagesGetNonEmptySpans() {
        assertFalse(SyntaxHighlighter.spans("def f(): pass", new File("main.py")).isEmpty());
        assertFalse(SyntaxHighlighter.spans("<p class=\"x\">", new File("index.html")).isEmpty());
        assertFalse(SyntaxHighlighter.spans("<?xml version=\"1.0\"?><a/>", new File("pom.xml")).isEmpty());
        assertEquals(SyntaxHighlighter.spansIn("def f(): pass", Language.PYTHON), SyntaxHighlighter.spans("def f(): pass", new File("main.py")));
    }

    // YAML

    @Test void yamlKeysScalarsCommentsAndMarkersAreColoured() {
        String yaml = "---\n# settings\nname: \"My App\"  # trailing\nport: 8080\nratio: -0.5e3\ndebug: true\nnothing: ~\nhome: /usr/local\n...\n";
        assertEquals(List.of("annotation:---", "comment:# settings", "type:name", "string:\"My App\"", "comment:# trailing", "type:port", "number:8080",
            "type:ratio", "number:-0.5e3", "type:debug", "keyword:true", "type:nothing", "keyword:~", "type:home", "annotation:..."), styled(yaml, "app.yml"));
    }

    @Test void yamlSequencesNestedMappingsAndQuotedKeys() {
        String yaml = "steps:\n  - name: build\n    run: mvn verify\n  - - nested\n  - \"quoted key\": 1\n";
        assertEquals(List.of("type:steps", "type:name", "type:run", "type:\"quoted key\"", "number:1"), styled(yaml, "ci.yaml"));
    }

    @Test void yamlAnchorsAliasesAndTags() {
        String yaml = "base: &base\n  a: 1\nderived:\n  <<: *base\n  id: !!str 42\n";
        assertEquals(List.of("type:base", "annotation:&base", "type:a", "number:1", "type:derived", "type:<<", "annotation:*base", "type:id", "annotation:!!str", "number:42"),
            styled(yaml, "a.yml"));
    }

    @Test void yamlFlowCollectionsColourKeysValuesAndStrings() {
        assertEquals(List.of("type:list", "number:1", "keyword:true", "string:\"s\""), styled("list: [a, 1, true, \"s\"]", "a.yml"));
        assertEquals(List.of("type:map", "type:k", "type:n", "keyword:null", "type:'q'", "number:2"), styled("map: {k: v, n: null, 'q': 2}", "a.yml"));
        assertEquals(List.of("type:k", "number:1"), styled("{k: 1}", "a.yml"), "a flow mapping that opens the line");
        assertEquals(List.of("type:a"), styled("{a: [b, c]", "a.yml"), "an unclosed flow collection stays harmless");
        assertEquals(List.of("number:1"), styled("[1]]", "a.yml"), "a stray closing bracket is ignored");
    }

    @Test void yamlBlockScalarsAreStringsUntilTheIndentationEnds() {
        String yaml = "script: |\n  echo \"# not a comment\"\n  key: value\n\n  more\nnext: 1\nfolded: >-  # about it\n  some text\nlast: false\n";
        assertEquals(List.of("type:script", "annotation:|", "string:echo \"# not a comment\"", "string:key: value", "string:more", "type:next", "number:1",
            "type:folded", "annotation:>-", "comment:# about it", "string:some text", "type:last", "keyword:false"), styled(yaml, "a.yml"));
    }

    @Test void yamlBlockScalarsInSequencesAndAfterAnchorsOrTags() {
        assertEquals(List.of("annotation:|", "string:text", "number:1"), styled("- |\n  text\n- 1\n", "a.yml"));
        assertEquals(List.of("type:k", "annotation:&a", "annotation:|2+", "string:x", "type:j"), styled("k: &a |2+\n    x\nj:\n", "a.yml"));
        assertEquals(List.of("type:k", "annotation:!!str", "annotation:>", "string:body"), styled("k: !!str >\n  body", "a.yml"));
    }

    @Test void yamlLookalikesStayPlain() {
        assertEquals(List.of("type:url"), styled("url: http://example.com/a#b", "a.yml"), "# without a space before it is part of the text");
        assertEquals(List.of("type:plain text with a"), styled("plain text with a: colon:inside and 1.2.3 and yes", "a.yml"), "only the text before the colon is a key");
        assertEquals(List.of("keyword:Null", "keyword:TRUE"), styled("[Null, TRUE]", "a.yml"));
        assertEquals(List.of(), styled("[yes, no, on, off, truthy, nullable, 1.2.3, 12ab, -, +]", "a.yml"), "only the YAML 1.2 literals and real numbers are coloured");
        assertEquals(List.of("type:a"), styled("a: | not a header", "a.yml"), "text after | makes it plain text, not a block scalar");
        assertEquals(List.of("type:a"), styled("a: b:c", "a.yml"), "a colon without a space after it is part of the value");
        assertEquals(List.of(), styled("---not a marker", "a.yml"));
        assertEquals(List.of(), styled("- notalist-item", "a.yml"));
        assertEquals(List.of("annotation:&"), styled("&", "a.yml"));
        assertEquals(List.of("annotation:%YAML 1.2"), styled("%YAML 1.2", "a.yml"), "a directive only counts in the first column");
        assertEquals(List.of(), styled("  %not a directive", "a.yml"));
    }

    @Test void yamlNumbersInAllTheirForms() {
        for (String number : new String[] {"0", "42", "-7", "+3", "3.14", ".5", "5.", "1e3", "1.5E-3", "2e+8", "0x1F", "0o17", ".inf", "-.inf", "+.Inf", ".NaN", ".NAN", ".nan"})
            assertEquals(List.of("number:" + number), styled("[" + number + "]", "a.yml"), number);
        for (String notNumber : new String[] {"-", ".", "1e", "1e+", "0x", "0xZZ", "0o9", "0o", "-.nan", "1.2.3", "abc", "1_000x"})
            assertEquals(List.of(), styled("[" + notNumber + "]", "a.yml"), notNumber);
    }

    @Test void yamlQuotedStringsEndWithTheirQuoteOrTheLine() {
        assertEquals(List.of("type:a", "string:'it''s'", "comment:# c"), styled("a: 'it''s' # c", "a.yml"));
        assertEquals(List.of("type:a", "string:\"say \\\"hi\\\"\"", "number:2"), styled("a: \"say \\\"hi\\\"\" 2", "a.yml"));
        assertEquals(List.of("type:a", "string:\"never closed"), styled("a: \"never closed", "a.yml"));
        assertEquals(List.of("string:'also never closed"), styled("'also never closed", "a.yml"));
        assertEquals(List.of("type:it's", "string:'x'"), styled("it's: 'x'", "a.yml"), "an apostrophe inside a plain key is not a quote");
    }

    @Test void yamlHandlesWindowsLineEndingsBlankLinesAndEmptyInput() {
        assertEquals(List.of("type:a", "number:1", "type:b", "keyword:true"), styled("a: 1\r\n\r\n   \r\nb: true\r\n", "a.yml"));
        assertEquals(List.of(), styled("", "a.yml"));
        assertEquals(List.of(), styled("\n\n", "a.yml"));
        assertEquals(List.of("type:k", "annotation:|", "string:x"), styled("k: |\r\n  x", "a.yml"), "a block scalar can end the file without a final newline");
        assertEquals(List.of("type:a", "comment:#c"), styled("a: #c", "a.yml"));
    }

    @Test void yamlStaysFastOnLongLinesAndIsOffForHugeFiles() {
        String long1 = "k: " + "x ".repeat(200_000) + "\n" + "[".repeat(100_000) + "\n" + "\"".repeat(100_000);
        long start = System.nanoTime();
        SyntaxHighlighter.spans(long1, new File("big.yml"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
        assertTrue(SyntaxHighlighter.spansIn("a: 1\n".repeat(SyntaxHighlighter.MAX_CHARS / 4), Language.YAML).isEmpty());
    }

    // Python

    @Test void pythonKeywordsTypesAndDecoratorsAreHighlighted() {
        assertEquals(List.of("annotation:@dataclass", "keyword:class", "keyword:def", "type:int", "type:str", "keyword:return", "keyword:None"),
            styled("@dataclass\nclass A:\n    def f(self, x: int) -> str:\n        return None", "a.py"));
    }

    @Test void everyPythonKeywordAndBuiltinTypeIsHighlighted() {
        for (String word : "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield".split(" ")) {
            assertEquals(List.of("keyword:" + word), styled(word, "a.py"), word);
        }
        for (String word : "bool bytearray bytes complex dict float frozenset int list object range set str tuple type Exception".split(" ")) {
            assertEquals(List.of("type:" + word), styled(word, "a.py"), word);
        }
    }

    // Python soft keywords (BUGS.md: "no contextual keywords such as ... Python's soft keywords `match`/`case`")

    @Test void matchAndCaseStartingAStatementAreKeywords() {
        String code = "match command:\n    case 'go':\n        pass\n    case _:\n        pass\n";
        assertEquals(List.of("keyword:match", "keyword:case", "string:'go'", "keyword:pass", "keyword:case", "keyword:pass"), styled(code, "a.py"));
    }

    @Test void aCaseBodyOnTheSameLineStillMakesItAKeyword() {
        assertEquals(List.of("keyword:case", "number:1", "keyword:return", "number:2"), styled("    case 1: return 2", "a.py"));
        assertEquals(List.of("keyword:case", "keyword:None", "keyword:case", "keyword:True"), styled("case None: x\ncase True: y", "a.py"));
    }

    @Test void softKeywordsMayBeIndentedWithTabsAndFollowedByCommentsOrBrackets() {
        assertEquals(List.of("keyword:match", "comment:# why"), styled("\t\tmatch x:  # why", "a.py"));
        assertEquals(List.of("keyword:match", "string:\"k\""), styled("match d[\"k\"]:", "a.py"));
        assertEquals(List.of("keyword:case", "number:1", "keyword:if"), styled("case [1, *rest] if rest:", "a.py").subList(0, 3));
        assertEquals(List.of("keyword:case", "string:\"a\"", "number:1"), styled("case {\"a\": 1}:", "a.py"));
        assertEquals(List.of("keyword:match", "number:1"), styled("match (1):", "a.py"));
        assertEquals(List.of("keyword:match", "number:1"), styled("match -1:", "a.py"));
    }

    @Test void softKeywordsAreRecognisedWithWindowsLineEndings() {
        assertEquals(List.of("keyword:match", "keyword:case", "number:1"), styled("match x:\r\n  case 1:\r\n", "a.py"));
    }

    @Test void matchAndCaseUsedAsNamesStayPlain() {
        assertEquals(List.of(), styled("match = re.match(pattern, text)", "a.py"));
        assertEquals(List.of("number:1"), styled("match.group(1)", "a.py"));
        assertEquals(List.of("number:3"), styled("case = 3", "a.py"));
        assertEquals(List.of("type:str"), styled("case: str", "a.py"));
        assertEquals(List.of("type:int", "number:1"), styled("match: int = 1", "a.py"));
        assertEquals(List.of(), styled("print(match, case)", "a.py"));
        assertEquals(List.of(), styled("match(x)", "a.py"));
        assertEquals(List.of("number:0"), styled("match[0]", "a.py"));
        assertEquals(List.of(), styled("match += x", "a.py"));
        assertEquals(List.of(), styled("match", "a.py"));
        assertEquals(List.of(), styled("match\n", "a.py"));
        assertEquals(List.of(), styled("case ", "a.py"));
    }

    @Test void matchAndCaseInsideOtherCodeStayPlain() {
        assertEquals(List.of("keyword:for", "keyword:in"), styled("x = [match for match in items]", "a.py"));
        assertEquals(List.of("keyword:if", "keyword:else", "number:0"), styled("y = match if case else 0", "a.py"));
        assertEquals(List.of("keyword:if"), styled("if x: match = y", "a.py"));
    }

    @Test void aStatementWithoutAColonIsNotAMatch() {
        assertEquals(List.of("comment:# note: stuff"), styled("match x  # note: stuff", "a.py"));
        assertEquals(List.of("string:\"a:b\""), styled("match \"a:b\"", "a.py"));
        assertEquals(List.of("number:1", "number:2"), styled("match d[1:2]", "a.py"));
        assertEquals(List.of("number:1"), styled("match (x := 1)", "a.py"));
        assertEquals(List.of(), styled("match x := y", "a.py"));
    }

    @Test void wordsThatCannotBeASubjectAreNotAMatch() {
        assertEquals(List.of("keyword:in"), styled("match in x:", "a.py"));
        assertEquals(List.of("keyword:is"), styled("case is x:", "a.py"));
    }

    @Test void softKeywordsInStringsAndCommentsStayHidden() {
        assertEquals(List.of("comment:# match x:"), styled("# match x:", "a.py"));
        assertEquals(List.of("string:\"\"\"\nmatch x:\n    case 1:\n\"\"\""), styled("\"\"\"\nmatch x:\n    case 1:\n\"\"\"", "a.py"));
        assertEquals(List.of("string:'match x:'"), styled("'match x:'", "a.py"));
    }

    @Test void onlyPythonTreatsMatchAndCaseAsSoftKeywords() {
        assertEquals(List.of("number:1"), styled("match x:\n case 1:", "a.kt"));
        assertEquals(List.of("number:1"), styled("match x:\n case 1:", "a.json"));
        assertEquals(List.of("keyword:case"), styled("case x:", "a.java")); // Java: `case` is always a keyword, `match` never
        assertEquals(List.of("keyword:match"), styled("match x:", "a.rs"));
    }

    @Test void softKeywordsKeepTheTextScanLinear() {
        String text = "match x:\n    case 1:\n        pass\n".repeat(25_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertEquals(25_000 * 4, SyntaxHighlighter.spansIn(text, Language.PYTHON).size()));
        String longLine = "match " + "a, ".repeat(200_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertTrue(SyntaxHighlighter.spansIn(longLine, Language.PYTHON).isEmpty()));
    }

    @Test void pythonKnowsItsOwnKeywordsNotJavasOrOthers() {
        assertEquals(List.of(), styled("public static void new implements synchronized String let fn func val", "a.py"));
        assertEquals(List.of("keyword:class", "keyword:import", "keyword:try"), styled("class import try", "a.py"));
        assertEquals(List.of(), styled("lambda_ nonlocals defs island", "a.py"));
        assertEquals(List.of("keyword:match", "keyword:case", "number:1"), styled("match x:\n case 1:", "a.py")); // soft keywords, see below
    }

    @Test void hashStartsAPythonCommentButSlashesDoNot() {
        assertEquals(List.of("number:1", "comment:# it's here"), styled("1 # it's here", "a.py"));
        assertEquals(List.of("number:7", "number:2"), styled("7 // 2", "a.py"));
        assertEquals(List.of("number:1", "number:2"), styled("1 /* 2 */", "a.py"));
        assertEquals(List.of("comment:# first", "number:2"), styled("# first\n2", "a.py"));
        assertEquals(List.of("comment:#"), styled("#", "a.py"));
        assertEquals(List.of("string:\"# not a comment\"", "comment:# real"), styled("\"# not a comment\"  # real", "a.py"));
        assertEquals(List.of("keyword:class"), styled("# class", "A.java")); // # is only a comment in Python
    }

    @Test void tripleQuotedPythonStringsSpanLines() {
        assertEquals(List.of("string:\"\"\"doc\nclass 'x' # y\n\"\"\"", "number:1"), styled("\"\"\"doc\nclass 'x' # y\n\"\"\" 1", "a.py"));
        assertEquals(List.of("string:'''it's \"fine\"\n'''", "number:1"), styled("'''it's \"fine\"\n''' 1", "a.py"));
        assertEquals(List.of("string:\"\"\"a \"b\" \"\"c\"\" d\"\"\""), styled("\"\"\"a \"b\" \"\"c\"\" d\"\"\"", "a.py"));
        assertEquals(List.of("string:\"\"\"\"\"\"", "number:1"), styled("\"\"\"\"\"\" 1", "a.py"));
    }

    @Test void aBackslashEscapesAQuoteInsideATripleQuotedPythonString() {
        assertEquals(List.of("string:\"\"\"a \\\"\"\" b\"\"\"", "number:1"), styled("\"\"\"a \\\"\"\" b\"\"\" 1", "a.py"));
    }

    @Test void anUnterminatedTripleQuotedPythonStringRunsToTheEnd() {
        assertEquals(List.of("string:\"\"\"open\nclass"), styled("\"\"\"open\nclass", "a.py"));
        assertEquals(List.of("string:'''abc\\"), styled("'''abc\\", "a.py"));
        assertEquals(List.of("string:'''"), styled("'''", "a.py"));
    }

    @Test void pythonEmptyAndEscapedStrings() {
        assertEquals(List.of("string:\"\"", "string:''"), styled("\"\" ''", "a.py"));
        assertEquals(List.of("string:'don\\'t'", "number:1"), styled("'don\\'t' 1", "a.py"));
    }

    @Test void pythonStringPrefixesAreColouredWithTheString() {
        assertEquals(List.of("string:f\"x{1}\"", "string:rb'raw'", "string:R\"x\"", "string:u'u'", "string:Rb\"B\"", "string:f'''t'''", "string:b\"\"\"t\"\"\""),
            styled("f\"x{1}\" rb'raw' R\"x\" u'u' Rb\"B\" f'''t''' b\"\"\"t\"\"\"", "a.py"));
        assertEquals(List.of("string:f\"x\"", "string:r'a'"), styled("(f\"x\") =r'a'", "a.py"));
        assertEquals(List.of("string:r'a'"), styled("r'a'", "a.py"));
    }

    @Test void lettersBeforeAQuoteAreOnlyAPrefixWhenTheyAreAWholeWord() {
        assertEquals(List.of("string:\"x\""), styled("bar\"x\"", "a.py"));
        assertEquals(List.of("string:\"x\""), styled("xb\"x\"", "a.py"));
        assertEquals(List.of("string:'x'"), styled("4f'x'", "a.py"));
        assertEquals(List.of("keyword:if", "string:\"x\""), styled("if\"x\"", "a.py"));
        assertEquals(List.of("string:'a'"), styled("'a'", "a.py"));
        assertEquals(List.of("string:\"x\""), styled("f\"x\"", "A.java")); // prefixes are Python's only
    }

    @Test void aStrayQuoteInPythonStaysOnItsLine() {
        assertEquals(List.of("keyword:class"), styled("x = 'oops\nclass Foo:", "a.py"));
        assertEquals(List.of("comment:# it's", "string:'a'"), styled("# it's\n'a'", "a.py"));
        assertEquals(List.of("keyword:class", "string:'ok'"), styled("don't\nclass 'ok'", "a.py"));
    }

    @Test void decoratorsMayBeDottedInPython() {
        assertEquals(List.of("annotation:@app.route", "string:\"/\""), styled("@app.route(\"/\")", "a.py"));
        assertEquals(List.of("annotation:@a.b.c"), styled("@a.b.c", "a.py"));
        assertEquals(List.of("annotation:@a"), styled("@a.", "a.py"));
        assertEquals(List.of("annotation:@a", "number:1"), styled("@a.1", "a.py"));
        assertEquals(List.of("annotation:@property"), styled("@property", "a.py"));
        assertEquals(List.of("annotation:@a"), styled("@a.b", "A.java")); // Java annotation names are not dotted
    }

    @Test void theMatrixMultiplicationOperatorIsNotADecorator() {
        assertEquals(List.of(), styled("x = a @ b", "a.py"));
        assertEquals(List.of("number:2"), styled("a @ 2", "a.py"));
        assertEquals(List.of(), styled("a @", "a.py"));
    }

    @Test void aPythonModuleIsHighlighted() {
        String code = "#!/usr/bin/env python3\n\"\"\"Doc.\"\"\"\nimport os\n\n@app.route(\"/\")\ndef main(argv: list[str]) -> int:\n    # it's fine\n"
            + "    name = f\"hi {argv[0]}\"  # greet\n    return 0 if name else 1\n";
        assertEquals(List.of("comment:#!/usr/bin/env python3", "string:\"\"\"Doc.\"\"\"", "keyword:import", "annotation:@app.route", "string:\"/\"", "keyword:def", "type:list",
            "type:str", "type:int", "comment:# it's fine", "string:f\"hi {argv[0]}\"", "comment:# greet", "keyword:return", "number:0", "keyword:if", "keyword:else", "number:1"),
            styled(code, "main.py"));
    }

    // HTML and XML

    @Test void htmlTagsAttributesAndValuesAreHighlighted() {
        assertEquals(List.of("keyword:div", "type:class", "string:\"a b\"", "type:id", "string:'b'", "type:hidden", "keyword:div"),
            styled("<div class=\"a b\" id='b' hidden>text</div>", "a.html"));
        assertEquals(List.of("keyword:br"), styled("<br/>", "a.html"));
        assertEquals(List.of("keyword:img", "type:src", "string:\"a.png\"", "type:alt", "string:\"x\""), styled("<img src=\"a.png\" alt=\"x\" />", "a.html"));
    }

    @Test void textBetweenTagsIsNeverColoured() {
        assertEquals(List.of("keyword:p", "keyword:p"), styled("<p>don't \"quote\" // class 42 /* x */ # y</p>", "a.html"));
        assertEquals(List.of("keyword:p", "keyword:p"), styled("<p>don't \"quote\" class 42</p>", "a.xml"));
        assertEquals(List.of(), styled("no markup here, it's \"plain\" 42", "a.html"));
    }

    @Test void aLessThanSignThatDoesNotStartATagIsPlain() {
        assertEquals(List.of(), styled("a < b, 1 <2 and </ > or <> or <3 <", "a.html"));
        assertEquals(List.of(), styled("a < b, 1 <2 and </ > or <> or <3 <", "a.xml"));
        assertEquals(List.of(), styled("<", "a.html"));
        assertEquals(List.of(), styled("</", "a.html"));
    }

    @Test void markupCommentsHideTheirContents() {
        assertEquals(List.of("comment:<!-- <b class=\"x\"> it's -->", "keyword:i"), styled("<!-- <b class=\"x\"> it's --><i", "a.html"));
        assertEquals(List.of("comment:<!-- never closed <b>"), styled("<!-- never closed <b>", "a.xml"));
        assertEquals(List.of("comment:<!---->"), styled("<!---->", "a.xml"));
        assertEquals(List.of("comment:<!-- a\nb -->"), styled("<!-- a\nb -->", "a.html"));
    }

    @Test void declarationsAndProcessingInstructionsAreAnnotations() {
        assertEquals(List.of("annotation:<!DOCTYPE html>", "keyword:html"), styled("<!DOCTYPE html>\n<html", "a.html"));
        assertEquals(List.of("annotation:<!DOCTYPE note"), styled("<!DOCTYPE note", "a.xml"));
        assertEquals(List.of("annotation:<?xml version=\"1.0\"?>", "keyword:root"), styled("<?xml version=\"1.0\"?>\n<root/>", "a.xml"));
        assertEquals(List.of("annotation:<?php echo 1;"), styled("<?php echo 1;", "a.html"));
    }

    @Test void cdataSectionsAreStrings() {
        assertEquals(List.of("keyword:a", "string:<![CDATA[ <b> \"x\" ]]>", "keyword:a"), styled("<a><![CDATA[ <b> \"x\" ]]></a>", "a.xml"));
        assertEquals(List.of("string:<![CDATA[ open <b>"), styled("<![CDATA[ open <b>", "a.xml"));
    }

    @Test void namespacesAndDashedNamesAreOneWord() {
        assertEquals(List.of("keyword:xsl:template", "type:match", "string:\"/\"", "type:xml:lang", "string:\"en\"", "keyword:my-element", "type:data-id", "string:'1'",
            "keyword:a.b_c", "keyword:_x:y", "keyword:xsl:template"),
            styled("<xsl:template match=\"/\" xml:lang=\"en\"> <my-element data-id='1'> <a.b_c> <_x:y> </xsl:template>", "a.xsl"));
    }

    @Test void unquotedAttributeValuesAreLeftPlain() {
        assertEquals(List.of("keyword:a", "type:href", "type:target"), styled("<a href=foo/bar target=_blank>", "a.html"));
        assertEquals(List.of("keyword:a", "type:href", "string:\"x\""), styled("<a href = \"x\">", "a.html"));
        assertEquals(List.of("keyword:a", "type:x"), styled("<a x=>b c", "a.html"));
        assertEquals(List.of("keyword:a", "type:x"), styled("<a x=", "a.html"));
        assertEquals(List.of("keyword:a", "type:x"), styled("<a x=\n  y>", "a.html"));
    }

    @Test void tagsAndQuotedValuesMaySpanLines() {
        assertEquals(List.of("keyword:a", "type:href", "string:\"x\ny\"", "type:title"), styled("<a\n  href=\"x\ny\"\n  title\n>text", "a.html"));
    }

    @Test void anUnterminatedTagOrValueRunsToTheEnd() {
        assertEquals(List.of("keyword:a", "type:href", "string:\"x\"", "type:class"), styled("<a href=\"x\" class", "a.html"));
        assertEquals(List.of("keyword:a", "type:x", "string:\"never <b> closed"), styled("<a x=\"never <b> closed", "a.xml"));
        assertEquals(List.of("keyword:a"), styled("<a", "a.xml"));
    }

    @Test void scriptContentIsHighlightedAsJavaScriptInHtml() {
        assertEquals(List.of("keyword:script", "keyword:const", "number:1", "comment:// c", "keyword:script"), styled("<script>const x = 1; // c\n</script>", "a.html"));
        assertEquals(List.of("keyword:SCRIPT", "keyword:let", "number:2", "keyword:Script"), styled("<SCRIPT>let a = 2</Script>", "a.htm"));
        assertEquals(List.of("keyword:script", "type:src", "string:\"a.js\"", "keyword:script"), styled("<script src=\"a.js\"></script>", "a.html"));
        assertEquals(List.of("keyword:script", "string:\"<b>\"", "keyword:script"), styled("<script>\"<b>\"</script>", "a.html"));
        assertEquals(List.of("keyword:script", "keyword:let", "number:1"), styled("<script>let a = 1", "a.html"));
    }

    @Test void scriptSpansHaveTheirOffsetInTheDocument() {
        String html = "<p>x</p><script type=\"module\">const k = 'k'</script><i>";
        assertEquals(List.of("keyword:p", "keyword:p", "keyword:script", "type:type", "string:\"module\"", "keyword:const", "string:'k'", "keyword:script", "keyword:i"),
            styled(html, "a.html"));
        int end = 0;
        for (Span s : SyntaxHighlighter.spans(html, new File("a.html"))) { assertTrue(s.start() >= end); end = s.end(); }
    }

    @Test void aSelfClosedScriptTagDoesNotSwallowTheRestOfTheDocument() {
        assertEquals(List.of("keyword:script", "type:src", "string:\"x\""), styled("<script src=\"x\"/>class", "a.html"));
    }

    @Test void styleContentIsSkippedInHtml() {
        assertEquals(List.of("keyword:style", "keyword:style"), styled("<style>a > b { content: \"<i>\" } /* c */ it's</style>", "a.html"));
        assertEquals(List.of("keyword:STYLE", "keyword:style", "keyword:b"), styled("<STYLE>x <b></style><b>", "a.html"));
        assertEquals(List.of("keyword:style"), styled("<style>p { } <b>", "a.html"));
    }

    @Test void scriptAndStyleAreOrdinaryElementsInXml() {
        assertEquals(List.of("keyword:script", "keyword:script"), styled("<script>class \"x\"</script>", "a.xml"));
        assertEquals(List.of("keyword:style", "keyword:style"), styled("<style>\"x\"</style>", "a.xml"));
        assertEquals(List.of("keyword:scripts", "keyword:scripts"), styled("<scripts>\"x</scripts>", "a.html"));
    }

    @Test void markupSpansNeverOverlapAndStayInOrderForEveryLanguage() {
        String python = "#!/usr/bin/env python3\n\"\"\"Doc.\"\"\"\n@app.route(\"/\")\ndef main(a: int) -> str:\n    return f\"x {a}\"  # it's\n";
        String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<style>p { color: red; } /* c */</style>\n<script>const a = \"x\"; // c\nfunction f() { return 1; }</script>\n</head>\n"
            + "<body class='x' data-a=1>\n<!-- c --><p>it's <b>bold</b> <![CDATA[ x ]]><?pi?></p><br/>\n</body></html>\n";
        String xml = "<?xml version=\"1.0\"?>\n<project xmlns:x=\"u\"><!-- c --><name>a &amp; b</name><x:item id=\"1\"/><![CDATA[ <z> ]]></project>\n";
        for (Language language : Language.values()) {
            for (String sample : List.of(python, html, xml, "<a \"x' <!-- '''\n\"\"\" #")) {
                String text = sample.repeat(20);
                int end = 0;
                for (Span s : SyntaxHighlighter.spansIn(text, language)) {
                    assertTrue(s.start() >= end, language + " overlap at " + s.start());
                    assertTrue(s.end() > s.start(), language + " empty span at " + s.start());
                    end = s.end();
                }
                assertTrue(end <= text.length(), language.name());
            }
        }
    }

    @Test void aMegabyteOfPythonAndMarkupHighlightsWithinABudget() {
        String python = "def compute(name: str) -> int:\n    return len(name) + 42  # trailing 'comment'\n";
        String html = "<div class=\"row\" id='x'><p>it's text</p><!-- c --></div>\n";
        for (Language language : List.of(Language.PYTHON, Language.HTML, Language.XML)) {
            String unit = language == Language.PYTHON ? python : html;
            String text = unit.repeat(SyntaxHighlighter.MAX_CHARS / unit.length());
            assertTrue(text.length() <= SyntaxHighlighter.MAX_CHARS);
            long start = System.nanoTime();
            List<Span> spans = SyntaxHighlighter.spansIn(text, language);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertFalse(spans.isEmpty(), language.name());
            assertTrue(ms < 3_000, "highlighting 1 MB of " + language + " took " + ms + " ms");
        }
    }

    @Test void pathologicalPythonInputStaysLinear() {
        String[] nasty = {"'''".repeat(300_000), "#".repeat(800_000), "'a".repeat(400_000), "\"\"\"".repeat(250_000), "\\'''".repeat(200_000), "'" + "\\".repeat(800_000),
            "f'".repeat(400_000), "@".repeat(800_000), "@" + "a.".repeat(400_000), "@a.".repeat(260_000), "'''" + "\\".repeat(800_000), "rb\"".repeat(260_000)};
        for (String text : nasty) {
            assertTrue(text.length() <= SyntaxHighlighter.MAX_CHARS, "too long to be scanned: " + text.substring(0, 4));
            long start = System.nanoTime();
            SyntaxHighlighter.spansIn(text, Language.PYTHON);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 3_000, "pathological input took " + ms + " ms: " + text.substring(0, 4));
        }
    }

    @Test void pathologicalMarkupInputStaysLinear() {
        String[] nasty = {"<a ".repeat(300_000), "<a x=\"".repeat(150_000), "<!--".repeat(250_000), "<![CDATA[".repeat(110_000), "<?".repeat(500_000), "<!".repeat(500_000),
            "</".repeat(500_000), "<".repeat(1_000_000), "<a x=".repeat(150_000), "<script>".repeat(120_000), "<style>".repeat(140_000), "<script></script>".repeat(60_000),
            "<script>'a".repeat(90_000), "<a>".repeat(300_000), "<a '".repeat(200_000), "<a x=' ".repeat(120_000), "<script></script".repeat(60_000), "<a=".repeat(300_000)};
        for (Language language : List.of(Language.HTML, Language.XML)) {
            for (String text : nasty) {
                assertTrue(text.length() <= SyntaxHighlighter.MAX_CHARS, "too long to be scanned: " + text.substring(0, 4));
                long start = System.nanoTime();
                SyntaxHighlighter.spansIn(text, language);
                long ms = (System.nanoTime() - start) / 1_000_000;
                assertTrue(ms < 3_000, language + " pathological input took " + ms + " ms: " + text.substring(0, 4));
            }
        }
    }
}
