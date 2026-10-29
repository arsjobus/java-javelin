package com.javelin_ide.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the highlightable tokens (comments, strings, annotations, keywords, types, numbers) in Java-like source.
 * JavaFX-free so it can be tested on its own.
 *
 * <p>This is a hand-written scanner rather than a regular expression on purpose: Java's regex engine recurses once
 * per repetition of a group such as {@code (?:\\.|[^"\\])*}, so a long string literal (an embedded base64 blob, a
 * minified line) overflows the stack. The scanner does one pass in time linear in the text and cannot recurse.
 * Documents over {@link #MAX_CHARS} are not highlighted at all, so typing in a huge file never stalls the UI thread.
 *
 * <p>Strings and character literals end at the end of the line, so a stray quote cannot colour the rest of the
 * file; an unterminated block comment runs to the end of the text (as in most editors); text blocks
 * ({@code """}) may span lines.
 *
 * <p>Four lexical families are understood, and {@link #spans(String, File)} leaves files of any other kind (shell,
 * plain text, ...) unstyled instead of colouring an apostrophe in prose or a {@code #} comment as if it were Java:
 * <ul>
 *   <li><b>C-family</b> ({@code //} and {@code /* *}{@code /} comments, double and single quoted literals): Java,
 *       Kotlin, JavaScript, TypeScript, C, C++, Go, Rust, PHP and JSON.
 *   <li><b>Python</b>: {@code #} comments, single and triple quoted strings that may carry an {@code r}, {@code b},
 *       {@code f} or {@code u} prefix, dotted decorators such as {@code @app.route}, and the soft keywords {@code match}
 *       and {@code case} (only where they start a {@code match} statement or clause, see {@code isPythonSoftKeyword}).
 *   <li><b>Markup</b> (HTML and XML): comments, {@code <!DOCTYPE>} and {@code <?xml ...?>} declarations, CDATA
 *       sections, tag names (as keywords), attribute names (as types) and quoted attribute values. Only text inside a
 *       tag is ever coloured, so an apostrophe in the prose between tags stays plain. In HTML the content of a
 *       {@code <script>} element is highlighted as JavaScript and the content of a {@code <style>} element is left
 *       alone.
 *   <li><b>Markdown</b>: headings, fenced and inline code, emphasis, links, blockquotes and list markers.
 * </ul>
 *
 * <p>Keywords and well-known types come from a per-{@link Language} list chosen by the file's extension, so
 * {@code fn} is a keyword in a Rust file, {@code func} in Go, and {@code class} is not one in Go or JSON. An untitled
 * buffer is treated as Java, as the editor is Java-oriented.
 */
public final class SyntaxHighlighter {
    /** Documents longer than this get no highlighting. Roughly {@code FileIO.LARGE_FILE_BYTES} of ASCII text. */
    public static final int MAX_CHARS = 1 << 20;

    /** A highlighted range: {@code [start, end)} and the CSS style class to apply. */
    public record Span(int start, int end, String style) {}

    /** The languages the scanner knows. Each one follows the lexical rules of one {@link Syntax}. */
    public enum Language { JAVA, KOTLIN, JAVASCRIPT, TYPESCRIPT, C, CPP, GO, RUST, PHP, JSON, PYTHON, HTML, XML, MARKDOWN }

    /** How a language is tokenised: C-style comments and strings, Python, angle-bracket markup or Markdown. */
    private enum Syntax { C_FAMILY, PYTHON, MARKUP, MARKDOWN }

    /** What counts as a keyword or a type in one language, and whether {@code @name} is an annotation or decorator. */
    private record Lexicon(Set<String> keywords, Set<String> types, boolean annotations, Syntax syntax) {}

    private static final Map<Language, Lexicon> LEXICONS = new EnumMap<>(Language.class);

    private static Set<String> words(String list) { return list.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(list.trim().split("\\s+"))); }

    private static void define(Language language, String keywords, String types, boolean annotations) {
        define(language, keywords, types, annotations, Syntax.C_FAMILY);
    }

    private static void define(Language language, String keywords, String types, boolean annotations, Syntax syntax) {
        LEXICONS.put(language, new Lexicon(words(keywords), words(types), annotations, syntax));
    }

    private static final String C_KEYWORDS = "auto break case const continue default do else enum extern for goto if inline register restrict return "
        + "sizeof static struct switch typedef union volatile while";
    private static final String C_TYPES = "char double float int long short signed unsigned void size_t bool FILE int8_t int16_t int32_t int64_t "
        + "uint8_t uint16_t uint32_t uint64_t";
    private static final String JS_KEYWORDS = "async await break case catch class const continue debugger default delete do else export extends false finally "
        + "for function if import in instanceof let new null return static super switch this throw true try typeof undefined var void while with yield";
    private static final String JS_TYPES = "Array Boolean Date Error JSON Map Math Number Object Promise Set String Symbol";

    static {
        define(Language.JAVA, "abstract assert break case catch class continue default do else enum extends final finally for if implements import instanceof "
            + "interface new package private protected public return static super switch synchronized this throw throws try var void while record sealed yield",
            "boolean byte char double float int long short String Object Integer Long Double Float Boolean System List Map Set File", true);
        define(Language.KOTLIN, "abstract as break by class companion const constructor continue data do else enum false final for fun if import in init inline "
            + "interface internal is lateinit null object open override package private protected public return sealed super suspend this throw true try "
            + "typealias val var when while",
            "Any Array Boolean Byte Char Double Float Int List Long Map Nothing Set Short String Unit", true);
        define(Language.JAVASCRIPT, JS_KEYWORDS, JS_TYPES, true);
        define(Language.TYPESCRIPT, JS_KEYWORDS + " abstract as declare enum implements interface keyof namespace private protected public readonly type",
            JS_TYPES + " any bigint boolean never number object string symbol unknown", true);
        define(Language.C, C_KEYWORDS, C_TYPES, false);
        define(Language.CPP, C_KEYWORDS + " catch class constexpr delete explicit false final friend mutable namespace new noexcept nullptr operator override "
            + "private protected public template this throw true try typename using virtual",
            C_TYPES + " string vector map set std", false);
        define(Language.GO, "break case chan const continue default defer else fallthrough false for func go goto if import interface map nil package range "
            + "return select struct switch true type var",
            "any bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr", false);
        define(Language.RUST, "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return "
            + "self Self static struct super trait true type unsafe use where while",
            "Box Option Result String Vec bool char f32 f64 i8 i16 i32 i64 i128 isize str u8 u16 u32 u64 u128 usize", false);
        define(Language.PHP, "abstract and as break case catch class clone const continue declare default do echo else elseif extends false final finally fn for "
            + "foreach function global if implements include instanceof interface namespace new null or private protected public require return static switch "
            + "throw trait true try use var while yield",
            "array bool callable float int iterable mixed object string void", false);
        define(Language.JSON, "true false null", "", false);
        define(Language.PYTHON, "False None True and as assert async await break class continue def del elif else except finally for from global if import in "
            + "is lambda nonlocal not or pass raise return try while with yield",
            "bool bytearray bytes complex dict float frozenset int list object range set str tuple type Exception", true, Syntax.PYTHON);
        // Markup colours tags and attributes by position, not by word, so it needs no keyword or type list.
        define(Language.HTML, "", "", false, Syntax.MARKUP);
        define(Language.XML, "", "", false, Syntax.MARKUP);
        define(Language.MARKDOWN, "", "", false, Syntax.MARKDOWN);
    }

    /** The prefixes (lower case) that may precede the quote of a Python string literal. */
    private static final Set<String> PYTHON_STRING_PREFIXES = Set.of("r", "u", "b", "f", "br", "rb", "fr", "rf");

    /** Python's soft keywords: words that are keywords only in a {@code match} statement and ordinary names everywhere else. */
    private static final Set<String> PYTHON_SOFT_KEYWORDS = Set.of("match", "case");

    /** Words that cannot start a {@code match} subject or a {@code case} pattern, so {@code match in x:} is not a statement. */
    private static final Set<String> NOT_A_SUBJECT = Set.of("in", "is", "and", "or", "if", "else", "for", "as");

    /** Extensions (lower case, no dot) of files whose syntax the scanner understands, and the language each one uses. */
    private static final Map<String, Language> EXTENSIONS = Map.ofEntries(
        Map.entry("java", Language.JAVA), Map.entry("kt", Language.KOTLIN), Map.entry("kts", Language.KOTLIN),
        Map.entry("js", Language.JAVASCRIPT), Map.entry("mjs", Language.JAVASCRIPT), Map.entry("cjs", Language.JAVASCRIPT), Map.entry("jsx", Language.JAVASCRIPT),
        Map.entry("ts", Language.TYPESCRIPT), Map.entry("tsx", Language.TYPESCRIPT),
        Map.entry("c", Language.C), Map.entry("h", Language.CPP), // a header may be C or C++; the C++ list is a superset
        Map.entry("cpp", Language.CPP), Map.entry("cc", Language.CPP), Map.entry("cxx", Language.CPP), Map.entry("hpp", Language.CPP), Map.entry("hh", Language.CPP),
        Map.entry("go", Language.GO), Map.entry("rs", Language.RUST), Map.entry("php", Language.PHP), Map.entry("json", Language.JSON),
        Map.entry("py", Language.PYTHON), Map.entry("pyi", Language.PYTHON), Map.entry("pyw", Language.PYTHON),
        Map.entry("html", Language.HTML), Map.entry("htm", Language.HTML), Map.entry("xhtml", Language.HTML),
        Map.entry("xml", Language.XML), Map.entry("xsd", Language.XML), Map.entry("xsl", Language.XML), Map.entry("xslt", Language.XML),
        Map.entry("svg", Language.XML), Map.entry("fxml", Language.XML), Map.entry("plist", Language.XML), Map.entry("rss", Language.XML),
        Map.entry("atom", Language.XML), Map.entry("wsdl", Language.XML), Map.entry("md", Language.MARKDOWN), Map.entry("markdown", Language.MARKDOWN));

    private SyntaxHighlighter() {}

    /**
     * The language whose rules the file gets: {@link Language#JAVA} for an untitled buffer (the editor is
     * Java-oriented), the language of a known extension (C-family, Python, HTML, XML or Markdown), and {@code null} for any other
     * file or a file without an extension, which is shown as plain text.
     */
    public static Language languageOf(File file) {
        if (file == null) return Language.JAVA;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : EXTENSIONS.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** True when the file should get syntax highlighting, that is when {@link #languageOf(File)} knows its language. */
    public static boolean appliesTo(File file) { return languageOf(file) != null; }

    /** True when {@link #spans} would produce nothing because the text is too long. */
    public static boolean tooLarge(int length) { return length > MAX_CHARS; }

    /** Like {@link #spans(String)}, but empty when {@link #appliesTo(File)} says the file is not highlightable. */
    public static List<Span> spans(String text, File file) {
        Language language = languageOf(file);
        return language == null ? new ArrayList<>() : spansIn(text, language);
    }

    /** The tokens of Java text, see {@link #spansIn(String, Language)}. */
    public static List<Span> spans(String text) { return spansIn(text, Language.JAVA); }

    /** The tokens of the text in order, without overlaps, using the rules and keywords of the language. Empty for text over {@link #MAX_CHARS}. */
    public static List<Span> spansIn(String text, Language language) {
        Lexicon lexicon = LEXICONS.get(language);
        List<Span> spans = new ArrayList<>();
        if (tooLarge(text.length())) return spans;
        if (lexicon.syntax() == Syntax.MARKUP) markup(text, language == Language.HTML, spans);
        else if (lexicon.syntax() == Syntax.MARKDOWN) markdown(text, spans);
        else code(text, lexicon, language == Language.RUST, spans);
        return spans;
    }

    /** Finds Markdown block and inline tokens one line at a time; prose remains in the editor's normal text colour. */
    private static void markdown(String text, List<Span> spans) {
        int n = text.length();
        int lineStart = 0;
        char fence = 0;
        int fenceLength = 0;
        while (lineStart < n) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? n : newline;
            int contentEnd = lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;

            int indent = lineStart;
            while (indent < contentEnd && indent - lineStart < 4 && text.charAt(indent) == ' ') indent++;
            int markerLength = markdownRun(text, indent, contentEnd);
            char marker = indent < contentEnd ? text.charAt(indent) : 0;
            if (fence != 0) {
                if (marker == fence && markerLength >= fenceLength && text.substring(indent + markerLength, contentEnd).isBlank()) {
                    add(spans, lineStart, contentEnd, "annotation");
                    fence = 0;
                } else {
                    add(spans, lineStart, contentEnd, "string");
                }
            } else if (indent - lineStart <= 3 && (marker == '`' || marker == '~') && markerLength >= 3
                && (marker != '`' || text.substring(indent + markerLength, contentEnd).indexOf('`') < 0)) {
                add(spans, lineStart, contentEnd, "annotation");
                fence = marker;
                fenceLength = markerLength;
            } else {
                int headingEnd = markdownHeadingEnd(text, indent, contentEnd);
                if (headingEnd >= 0) {
                    add(spans, indent, headingEnd, "annotation");
                    markdownInline(text, headingEnd, contentEnd, "keyword", spans);
                } else if (isMarkdownRule(text, indent, contentEnd)) {
                    add(spans, indent, contentEnd, "comment");
                } else {
                    int body = indent;
                    if (body < contentEnd && text.charAt(body) == '>') {
                        int quoteEnd = body + 1;
                        if (quoteEnd < contentEnd && text.charAt(quoteEnd) == ' ') quoteEnd++;
                        add(spans, body, quoteEnd, "comment");
                        body = quoteEnd;
                    } else {
                        int listEnd = markdownListMarkerEnd(text, body, contentEnd);
                        if (listEnd > body) {
                            add(spans, body, listEnd, Character.isDigit(text.charAt(body)) ? "number" : "keyword");
                            body = listEnd;
                            if (body + 2 < contentEnd && text.charAt(body) == '['
                                && (text.charAt(body + 1) == ' ' || text.charAt(body + 1) == 'x' || text.charAt(body + 1) == 'X')
                                && text.charAt(body + 2) == ']') {
                                add(spans, body, body + 3, "annotation");
                                body += 3;
                            }
                        }
                    }
                    markdownInline(text, body, contentEnd, null, spans);
                }
            }
            lineStart = newline < 0 ? n : newline + 1;
        }
    }

    /** Highlights inline code, links and emphasis, with an optional style for otherwise plain heading text. */
    private static void markdownInline(String text, int start, int end, String plainStyle, List<Span> spans) {
        int i = start;
        int plainStart = start;
        boolean codeSearchDead = false, linkSearchDead = false, boldSearchDead = false, italicSearchDead = false;
        int linkSearchFrom = start, linkClose = -1;
        while (i < end) {
            char c = text.charAt(i);
            int tokenEnd = -1;
            if (c == '`' && !codeSearchDead) {
                int ticks = markdownRun(text, i, end);
                int close = findMarkdownRun(text, i + ticks, end, '`', ticks);
                if (close >= 0) tokenEnd = close + ticks;
                else codeSearchDead = true;
                if (tokenEnd > i) {
                    addMarkdownPlain(spans, plainStart, i, plainStyle);
                    add(spans, i, tokenEnd, "string");
                }
            } else if (c == '[' && !linkSearchDead) {
                int closeLabel = -1;
                if (i >= linkSearchFrom) {
                    if (linkClose < i + 1) linkClose = text.indexOf(']', i + 1);
                    closeLabel = linkClose;
                }
                if (closeLabel < 0 || closeLabel >= end) {
                    if (i >= linkSearchFrom) linkSearchDead = true;
                } else if (closeLabel + 1 >= end || text.charAt(closeLabel + 1) != '(') {
                    linkSearchFrom = closeLabel + 1;
                    linkClose = -1;
                } else {
                    int closeUrl = text.indexOf(')', closeLabel + 2);
                    if (closeUrl < 0 || closeUrl >= end) linkSearchDead = true;
                    else {
                        tokenEnd = closeUrl + 1;
                        addMarkdownPlain(spans, plainStart, i, plainStyle);
                        add(spans, i, i + 1, "annotation");
                        add(spans, i + 1, closeLabel, "type");
                        add(spans, closeLabel, closeLabel + 2, "annotation");
                        add(spans, closeLabel + 2, closeUrl, "string");
                        add(spans, closeUrl, tokenEnd, "annotation");
                    }
                }
            } else if ((c == '*' || c == '_') && i + 1 < end) {
                int run = markdownRun(text, i, end);
                if (run >= 2 && !boldSearchDead) {
                    int close = text.indexOf(text.substring(i, i + 2), i + 2);
                    if (close < 0 || close + 2 > end) boldSearchDead = true;
                    else {
                        tokenEnd = close + 2;
                        addMarkdownPlain(spans, plainStart, i, plainStyle);
                        add(spans, i, i + 2, "annotation");
                        add(spans, i + 2, close, "type");
                        add(spans, close, tokenEnd, "annotation");
                    }
                }
                if (tokenEnd < 0 && run == 1 && !italicSearchDead) {
                    int close = text.indexOf(c, i + 1);
                    if (close < 0 || close >= end) italicSearchDead = true;
                    else {
                        tokenEnd = close + 1;
                        addMarkdownPlain(spans, plainStart, i, plainStyle);
                        add(spans, i, i + 1, "annotation");
                        add(spans, i + 1, close, "type");
                        add(spans, close, tokenEnd, "annotation");
                    }
                }
            }
            if (tokenEnd > i) {
                i = tokenEnd;
                plainStart = i;
            } else {
                i++;
            }
        }
        addMarkdownPlain(spans, plainStart, end, plainStyle);
    }

    private static void addMarkdownPlain(List<Span> spans, int start, int end, String style) {
        if (style != null) add(spans, start, end, style);
    }

    private static int markdownHeadingEnd(String text, int start, int end) {
        int hashes = markdownRun(text, start, end);
        if (hashes < 1 || hashes > 6 || start + hashes < end && text.charAt(start + hashes) != ' ') return -1;
        int content = start + hashes;
        while (content < end && text.charAt(content) == ' ') content++;
        return content;
    }

    private static int markdownListMarkerEnd(String text, int start, int end) {
        if (start >= end) return start;
        int i = start;
        if (text.charAt(i) == '-' || text.charAt(i) == '+' || text.charAt(i) == '*') i++;
        else if (Character.isDigit(text.charAt(i))) {
            while (i < end && Character.isDigit(text.charAt(i))) i++;
            if (i < end && text.charAt(i) == '.') i++;
            else return start;
        } else return start;
        return i < end && text.charAt(i) == ' ' ? i + 1 : start;
    }

    private static boolean isMarkdownRule(String text, int start, int end) {
        char marker = 0;
        int count = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == ' ') continue;
            if (marker == 0) marker = c;
            if (c != marker || (marker != '-' && marker != '_' && marker != '*')) return false;
            count++;
        }
        return count >= 3;
    }

    private static int markdownRun(String text, int start, int end) {
        if (start >= end) return 0;
        char c = text.charAt(start);
        if (c != '`' && c != '~' && c != '#' && c != '*' && c != '_') return 0;
        int i = start + 1;
        while (i < end && text.charAt(i) == c) i++;
        return i - start;
    }

    private static int findMarkdownRun(String text, int from, int end, char marker, int length) {
        for (int i = from; i < end; i++) {
            if (text.charAt(i) != marker) continue;
            int run = markdownRun(text, i, end);
            if (run == length) return i;
            i += run - 1;
        }
        return -1;
    }

    /**
     * Scans C-family or Python source. In Rust ({@code rust}) a quote followed by a name that does not end in another quote
     * is a lifetime or loop label ({@code 'a}, {@code 'static}, {@code 'outer:}), not a character literal, and is left plain.
     */
    private static void code(String text, Lexicon lexicon, boolean rust, List<Span> spans) {
        boolean python = lexicon.syntax() == Syntax.PYTHON;
        int n = text.length();
        int i = 0;
        // Once a quote fails to close before the end of its line, no later quote of the same kind on that line can close
        // either (it would have been the closing quote, or sits inside an escape the first scan already skipped), so
        // remember where that line ends and skip them: without this a line of escaped quotes takes quadratic time.
        int doubleDeadUntil = -1, singleDeadUntil = -1;
        while (i < n) {
            char c = text.charAt(i);
            boolean slash = c == '/' && i + 1 < n;
            if (python ? c == '#' : slash && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                i = add(spans, i, end < 0 ? n : end, "comment");
            } else if (!python && slash && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                i = add(spans, i, close < 0 ? n : close + 2, "comment");
            } else if ((c == '"' || python && c == '\'') && text.startsWith(c == '"' ? "\"\"\"" : "'''", i)) {
                int start = python ? i - stringPrefixLength(text, i) : i;
                i = add(spans, start, tripleEnd(text, i, c, python), "string");
            } else if (rust && c == '#' && isRustAttribute(text, i)) {
                i = add(spans, i, rustAttributeEnd(text, i), "annotation");
            } else if (rust && c == '\'' && isLifetime(text, i)) {
                i = identifierEnd(text, i + 1);
            } else if (c == '"' || c == '\'') {
                if (i < (c == '"' ? doubleDeadUntil : singleDeadUntil)) { i++; continue; }
                int end = literalEnd(text, i, c);
                if (end >= 0) { add(spans, python ? i - stringPrefixLength(text, i) : i, end, "string"); i = end; continue; }
                if (c == '"') doubleDeadUntil = -end - 1; else singleDeadUntil = -end - 1;
                i++;
            } else if (lexicon.annotations() && c == '@' && i + 1 < n && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                i = add(spans, i, python ? dottedEnd(text, i + 1) : identifierEnd(text, i + 1), "annotation");
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(text, i);
                String word = text.substring(i, end);
                if (lexicon.keywords().contains(word) || python && isPythonSoftKeyword(text, i, end)) add(spans, i, end, "keyword");
                else if (lexicon.types().contains(word)) add(spans, i, end, "type");
                i = end;
            } else if (c >= '0' && c <= '9') {
                int end = digitsEnd(text, i);
                if (end + 1 < n && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1))) end = digitsEnd(text, end + 1);
                int word = identifierEnd(text, end); // 1x, 0x1F, 10L are one word, not a plain number
                if (word == end) add(spans, i, end, "number");
                i = word;
            } else {
                i++;
            }
        }
    }

    /**
     * Scans HTML or XML. Text between tags is never coloured; everything else is found by looking for the next
     * {@code <}. Each construct consumes what it scans, so the whole pass stays linear.
     */
    private static void markup(String text, boolean html, List<Span> spans) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            i = text.indexOf('<', i);
            if (i < 0) return;
            if (text.startsWith("<!--", i)) {
                i = add(spans, i, endOf(text, "-->", i + 4), "comment");
            } else if (text.startsWith("<![CDATA[", i)) {
                i = add(spans, i, endOf(text, "]]>", i + 9), "string");
            } else if (text.startsWith("<?", i)) {
                i = add(spans, i, endOf(text, "?>", i + 2), "annotation");
            } else if (text.startsWith("<!", i)) {
                i = add(spans, i, endOf(text, ">", i + 2), "annotation");
            } else if (i + 1 < n && isNameStart(text.charAt(i + 1))) {
                int nameFrom = i + 1;
                i = tag(text, nameFrom, spans);
                boolean selfClosed = i >= 2 && text.charAt(i - 1) == '>' && text.charAt(i - 2) == '/';
                if (html && !selfClosed) {
                    String name = text.substring(nameFrom, nameEnd(text, nameFrom)).toLowerCase(Locale.ROOT);
                    if (name.equals("script") || name.equals("style")) i = rawText(text, i, name, spans);
                }
            } else if (i + 2 < n && text.charAt(i + 1) == '/' && isNameStart(text.charAt(i + 2))) {
                i = tag(text, i + 2, spans);
            } else {
                i++;
            }
        }
    }

    /**
     * Colours one tag whose name starts at {@code nameFrom} (just after {@code <} or {@code </}): the name as a keyword,
     * attribute names as types and quoted values as strings. Returns the index just after the closing {@code >}, or
     * the end of the text when the tag is never closed. Quoted values may span lines, as they do in a browser.
     */
    private static int tag(String text, int nameFrom, List<Span> spans) {
        int n = text.length();
        int i = add(spans, nameFrom, nameEnd(text, nameFrom), "keyword");
        while (i < n) {
            char c = text.charAt(i);
            if (c == '>') return i + 1;
            if (c == '"' || c == '\'') {
                i = add(spans, i, endOf(text, String.valueOf(c), i + 1), "string");
            } else if (isNameStart(c)) {
                i = add(spans, i, nameEnd(text, i), "type");
            } else if (c == '=') {
                i++;
                while (i < n && Character.isWhitespace(text.charAt(i))) i++;
                if (i < n && text.charAt(i) != '"' && text.charAt(i) != '\'') { // an unquoted value is left uncoloured
                    while (i < n && text.charAt(i) != '>' && !Character.isWhitespace(text.charAt(i))) i++;
                }
            } else {
                i++;
            }
        }
        return n;
    }

    /**
     * Handles the content of a {@code <script>} or {@code <style>} element that starts at {@code from}: script is
     * highlighted as JavaScript, style is skipped so its {@code <} and quotes cannot be mistaken for markup. Returns
     * where the content ends, the start of the closing tag (or the end of the text).
     */
    private static int rawText(String text, int from, String name, List<Span> spans) {
        int close = indexOfIgnoreCase(text, "</" + name, from);
        int end = close < 0 ? text.length() : close;
        if (name.equals("script")) {
            for (Span span : spansIn(text.substring(from, end), Language.JAVASCRIPT)) spans.add(new Span(span.start() + from, span.end() + from, span.style()));
        }
        return end;
    }

    /** Index of the first {@code <}-led occurrence of {@code needle} at or after {@code from}, ignoring case, or -1. */
    private static int indexOfIgnoreCase(String text, String needle, int from) {
        for (int i = text.indexOf('<', from); i >= 0; i = text.indexOf('<', i + 1)) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    /** End (exclusive) of the construct closed by {@code close}, or the end of the text when it is never closed. */
    private static int endOf(String text, String close, int from) {
        int at = text.indexOf(close, from);
        return at < 0 ? text.length() : at + close.length();
    }

    private static boolean isNameStart(char c) { return Character.isLetter(c) || c == '_' || c == ':'; }

    private static int nameEnd(String text, int from) {
        int i = from, n = text.length();
        while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || "-_:.".indexOf(text.charAt(i)) >= 0)) i++;
        return i;
    }

    /**
     * End (exclusive) of a triple quoted string that opens at {@code start}, or the end of the text when it is never
     * closed. Python honours a backslash escape inside the string; Java text blocks are matched without regard to it.
     */
    private static int tripleEnd(String text, int start, char quote, boolean escapes) {
        String triple = quote == '"' ? "\"\"\"" : "'''";
        if (!escapes) return endOf(text, triple, start + 3);
        int n = text.length();
        for (int i = start + 3; i < n; i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.startsWith(triple, i)) return i + 3;
        }
        return n;
    }

    /**
     * True when the word at {@code [start, end)} is Python's {@code match} or {@code case} used as a soft keyword. These
     * are ordinary names (a variable, a function, {@code re.match}) everywhere else, so the word counts only when it is
     * the first token on its line, is followed by something that can begin a subject or pattern, and the line then has a
     * {@code :} outside any brackets, strings and comment ({@code case 1: return x} counts, {@code match = f(a: b)} does
     * not). A subject or pattern that continues over several lines is not recognised. Costs one pass over the rest of
     * the line, and only for these two words at the start of a line.
     */
    private static boolean isPythonSoftKeyword(String text, int start, int end) {
        if (!PYTHON_SOFT_KEYWORDS.contains(text.substring(start, end))) return false;
        for (int i = start - 1; i >= 0 && text.charAt(i) != '\n'; i--) {
            if (text.charAt(i) != ' ' && text.charAt(i) != '\t') return false;
        }
        int n = text.length();
        int next = end;
        while (next < n && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) next++;
        if (next == end || next >= n) return false; // `match(x)`, `match` alone and `match\n` are not statements
        char first = text.charAt(next);
        if (!(Character.isJavaIdentifierStart(first) || Character.isDigit(first) || "([{\"'-+*~".indexOf(first) >= 0)) return false;
        if (Character.isJavaIdentifierStart(first) && NOT_A_SUBJECT.contains(text.substring(next, identifierEnd(text, next)))) return false;
        char quote = 0;
        int depth = 0;
        for (int i = next; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') break;
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '#') {
                break;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ':' && depth == 0 && !(i + 1 < n && text.charAt(i + 1) == '=')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Length of the Python string prefix ({@code r}, {@code b}, {@code f}, {@code u} or a two letter mix) that sits
     * directly before the quote at {@code quote}, so {@code f"..."} is coloured as one string. Zero when the letters
     * before the quote are the end of a longer word, such as {@code bar"x"}.
     */
    private static int stringPrefixLength(String text, int quote) {
        for (int length = 2; length >= 1; length--) {
            int from = quote - length;
            if (from < 0 || from > 0 && Character.isJavaIdentifierPart(text.charAt(from - 1))) continue;
            if (PYTHON_STRING_PREFIXES.contains(text.substring(from, quote).toLowerCase(Locale.ROOT))) return length;
        }
        return 0;
    }

    /**
     * True when the quote at {@code quote} opens a Rust lifetime or label: it is followed by an identifier that is not
     * closed by a second quote. {@code 'a'} and {@code 'é'} are character literals; {@code 'a} and {@code 'static} are not.
     */
    private static boolean isLifetime(String text, int quote) {
        int from = quote + 1;
        if (from >= text.length() || !Character.isJavaIdentifierStart(text.charAt(from))) return false;
        int end = identifierEnd(text, from);
        return end >= text.length() || text.charAt(end) != '\'';
    }

    /** End of a dotted name such as {@code app.route}, starting with an identifier at {@code from}. */
    private static int dottedEnd(String text, int from) {
        int i = identifierEnd(text, from), n = text.length();
        while (i + 1 < n && text.charAt(i) == '.' && Character.isJavaIdentifierStart(text.charAt(i + 1))) i = identifierEnd(text, i + 1);
        return i;
    }

    /** True when the {@code #} at {@code hash} opens a Rust attribute: {@code #[name...]} or the inner form {@code #![name...]}. */
    private static boolean isRustAttribute(String text, int hash) {
        int n = text.length();
        int open = hash + 1;
        if (open < n && text.charAt(open) == '!') open++;
        return open + 1 < n && text.charAt(open) == '[' && Character.isJavaIdentifierStart(text.charAt(open + 1));
    }

    /**
     * End (exclusive) of the marker and path of the Rust attribute at {@code hash}: {@code #[derive}, {@code #![allow},
     * {@code #[tokio::main}. Like a Java annotation only the name is coloured; the arguments are scanned as ordinary code.
     */
    private static int rustAttributeEnd(String text, int hash) {
        int n = text.length();
        int i = hash + 1;
        if (text.charAt(i) == '!') i++;
        i = identifierEnd(text, i + 1); // skips the `[`, then the first path segment
        while (i + 2 < n && text.charAt(i) == ':' && text.charAt(i + 1) == ':' && Character.isJavaIdentifierStart(text.charAt(i + 2))) i = identifierEnd(text, i + 2);
        return i;
    }

    private static int add(List<Span> spans, int start, int end, String style) {
        spans.add(new Span(start, end, style));
        return end;
    }

    /** End (exclusive) of a quoted literal starting at {@code start}; when the line ends first, {@code -(index where it ended) - 1}. */
    private static int literalEnd(String text, int start, char quote) {
        int n = text.length();
        for (int i = start + 1; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && text.charAt(i + 1) != '\n') i++;
            else if (c == quote) return i + 1;
            else if (c == '\n') return -i - 1;
        }
        return -n - 1;
    }

    private static int identifierEnd(String text, int from) {
        int i = from, n = text.length();
        while (i < n && Character.isJavaIdentifierPart(text.charAt(i)) && !Character.isIdentifierIgnorable(text.charAt(i))) i++;
        return i;
    }

    private static int digitsEnd(String text, int from) {
        int i = from, n = text.length();
        while (i < n && text.charAt(i) >= '0' && text.charAt(i) <= '9') i++;
        return i;
    }
}
