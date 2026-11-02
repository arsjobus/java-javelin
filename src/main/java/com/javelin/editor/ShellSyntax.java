package com.javelin.editor;

import com.javelin.editor.SyntaxHighlighter.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the highlightable tokens of a POSIX-style shell script ({@code .sh}, {@code .bash}, {@code .zsh}, {@code .ksh},
 * {@code .command}). JavaFX-free, like {@link SyntaxHighlighter}, which hands shell files to {@link #scan}.
 *
 * <p>Like the other scanners this is lexical, not a parser. What it colours:
 * <ul>
 *   <li>{@code comment}: {@code #} at the start of a word, up to the end of the line (so {@code $#} and {@code a#b}
 *       are not comments). The {@code #!} line is an ordinary comment.
 *   <li>{@code string}: single quotes (which may span lines), double quotes (a {@code $(...)} inside one may contain
 *       quotes of its own), {@code $'...'}, {@code $"..."} and the body of here-documents.
 *   <li>{@code keyword}: reserved words ({@code if}, {@code then}, {@code for}, ... and {@code [[ ]]}), but only where
 *       the shell reads them as reserved, so {@code echo done} leaves {@code done} plain; the delimiter of a
 *       here-document is a keyword too.
 *   <li>{@code annotation}: builtin commands ({@code echo}, {@code cd}, {@code export}, ...), only in command position.
 *   <li>{@code type}: variables: {@code $name}, {@code ${...}}, the special parameters ({@code $1 $@ $? ...}) and the
 *       name in an assignment such as {@code NAME=value}.
 *   <li>{@code number}: a word made of digits only ({@code exit 1}, {@code -gt 10}).
 * </ul>
 *
 * <p>It tracks one thing a plain tokenizer cannot: whether a word is in <em>command position</em> (at the start of a
 * line, after {@code ; | & ( $(} or a backtick, after {@code then}/{@code do}/{@code else}, or after variable
 * assignments). That is what makes {@code echo if} and {@code git cd} plain while {@code if} and {@code cd} on their
 * own are coloured.
 *
 * <p>Known limits (see BUGS.md): nested {@code ${a:-${b}}} ends at the first {@code }}, {@code case} patterns are
 * not told apart from commands, and aliases or functions are not known. A quote with no partner on its line colours
 * only to the end of that line (a single quote that does have a partner later in the file pairs with it, as in a real
 * shell). The scan is a single pass: every unterminated construct is bounded by its line, so a long line of stray
 * quotes cannot make it quadratic.
 */
final class ShellSyntax {
    /** Reserved words (those that begin or end a compound command). {@code in} is handled separately, see {@code awaitingIn}. */
    private static final Set<String> RESERVED = Set.of("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "function",
        "select", "time", "coproc");

    /** Reserved words that are followed by a command. */
    private static final Set<String> COMMAND_FOLLOWS = Set.of("if", "then", "else", "elif", "while", "until", "do", "time", "coproc");

    /** Builtins and the usual shell commands that are part of the language rather than programs on the PATH. */
    private static final Set<String> BUILTINS = Set.of("echo", "printf", "cd", "pwd", "export", "unset", "local", "readonly", "declare", "typeset", "set", "shift",
        "source", ".", "eval", "exec", "exit", "return", "break", "continue", "read", "test", "[", "true", "false", ":", "alias", "unalias", "trap", "wait", "kill",
        "jobs", "fg", "bg", "umask", "ulimit", "type", "hash", "getopts", "let", "command", "builtin", "enable", "help", "history", "pushd", "popd", "dirs");

    /** The size a double-quoted string may reach before the scanner gives up on it, unless it ends on its own line. */
    private static final int QUOTE_REACH = 4096;

    private record Heredoc(String delimiter, boolean stripTabs) {}

    /** {@code indexOf} for one character that remembers its answer; valid because every question asks about a later position than the last. */
    private final class Finder {
        private final char target;
        private int cached = -2; // -2: not asked yet, -1: nothing left

        Finder(char target) { this.target = target; }

        int find(int from) {
            if (cached == -1) return -1;
            if (cached >= from) return cached;
            cached = text.indexOf(target, from);
            return cached;
        }
    }

    private final String text;
    private final int n;
    private final List<Span> spans;
    private final Finder newline = new Finder('\n');
    private final Finder singleQuote = new Finder('\'');
    private final Finder doubleQuote = new Finder('"');
    private final Finder closeBrace = new Finder('}');
    private final List<Heredoc> pending = new ArrayList<>();
    private int i;
    private boolean commandStart = true;
    private boolean awaitingIn; // after for/case/select: the word `in` is reserved
    private boolean inAssignment; // inside the value of NAME=value: a quote or $var there does not end command position
    private boolean inBackticks;
    private int arithmetic; // open `((` / `$((`: here `<<` is a shift, not a here-document
    private int substitutions; // open `$(`

    private ShellSyntax(String text, List<Span> spans) {
        this.text = text;
        this.n = text.length();
        this.spans = spans;
    }

    /** Appends the tokens of {@code text} in order, without overlaps. */
    static void scan(String text, List<Span> spans) { new ShellSyntax(text, spans).run(); }

    private void run() {
        while (i < n) {
            char c = text.charAt(i);
            if (inAssignment && isBoundary(c)) inAssignment = false;
            switch (c) {
                case ' ', '\t', '\r' -> i++;
                case '\n' -> lineBreak();
                case '\\' -> backslash();
                case '#' -> { if (atWordStart(i)) comment(); else word(); }
                case '\'' -> singleQuoted();
                case '"' -> doubleQuoted(i, i);
                case '$' -> dollar();
                case '`' -> { inBackticks = !inBackticks; commandStart = inBackticks; i++; }
                case '(' -> openParen();
                case ')' -> closeParen();
                case ';', '|' -> { i++; commandStart = true; awaitingIn = false; }
                case '&' -> ampersand();
                case '<' -> lessThan();
                case '>' -> i++;
                default -> word();
            }
        }
    }

    // ---- characters that end a word ----

    private static boolean isBoundary(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ';' || c == '&' || c == '|' || c == '(' || c == ')' || c == '<' || c == '>' || c == '`'; }

    private static boolean endsPlainWord(char c) { return isBoundary(c) || c == '\'' || c == '"' || c == '$'; }

    private boolean atWordStart(int at) { return at == 0 || isBoundary(text.charAt(at - 1)); }

    /** The end of the line that contains {@code at}, before its line break. */
    private int lineEnd(int at) {
        int nl = newline.find(at);
        int end = nl < 0 ? n : nl;
        return end > at && text.charAt(end - 1) == '\r' ? end - 1 : end;
    }

    /** A quote, variable or similar token ended: what follows is an argument, unless it is part of an assignment's value. */
    private void tokenEnded() { if (!inAssignment) commandStart = false; }

    private void add(int start, int end, String style) { if (end > start) spans.add(new Span(start, end, style)); }

    // ---- line breaks, comments, escapes ----

    private void lineBreak() {
        i++;
        commandStart = true;
        awaitingIn = false;
        inAssignment = false;
        if (!pending.isEmpty()) heredocBodies();
    }

    private void backslash() {
        if (i + 1 < n && text.charAt(i + 1) == '\n') i += 2; // line continuation: the command goes on
        else if (i + 2 < n && text.charAt(i + 1) == '\r' && text.charAt(i + 2) == '\n') i += 3;
        else word(); // an escaped character belongs to the word it is in
    }

    private void comment() {
        int end = lineEnd(i);
        add(i, end, "comment");
        i = end;
    }

    // ---- quotes ----

    private void singleQuoted() {
        int close = singleQuote.find(i + 1);
        int end = close < 0 ? lineEnd(i) : close + 1;
        add(i, end, "string");
        i = end;
        tokenEnded();
    }

    /** A double-quoted string whose quote is at {@code quote}; the coloured range starts at {@code from} ({@code $"} starts one earlier). */
    private void doubleQuoted(int from, int quote) {
        int end = doubleQuoteEnd(quote);
        if (end < 0) end = lineEnd(quote);
        add(from, end, "string");
        i = end;
        tokenEnded();
    }

    /**
     * The end (exclusive) of the double-quoted string that opens at {@code quote}, or -1 if it is not closed within reach.
     * A {@code $(...)} inside the string may hold quotes of its own, so this keeps a small stack of "inside a string" and
     * "inside a command substitution" instead of looking for the next quote.
     */
    private int doubleQuoteEnd(int quote) {
        int limit = Math.min(n, Math.max(lineEnd(quote), quote + QUOTE_REACH));
        int[] stack = new int[16];
        int depth = 0;
        stack[depth++] = 0; // 0: in a string, 1: in $( )
        for (int j = quote + 1; j < limit; j++) {
            char c = text.charAt(j);
            if (c == '\\') { j++; continue; }
            if (stack[depth - 1] == 0) {
                if (c == '"') { if (--depth == 0) return j + 1; }
                else if (c == '$' && j + 1 < n && text.charAt(j + 1) == '(') { depth = push(stack, depth, 1); j++; }
            } else if (c == '"') {
                depth = push(stack, depth, 0);
            } else if (c == '(') {
                depth = push(stack, depth, 1);
            } else if (c == ')') {
                depth--;
            } else if (c == '\'') {
                for (int k = j + 1; k < limit; k++) if (text.charAt(k) == '\'') { j = k; break; }
            }
            if (depth == stack.length) stack = java.util.Arrays.copyOf(stack, depth * 2);
        }
        return -1;
    }

    private static int push(int[] stack, int depth, int kind) {
        if (depth < stack.length) stack[depth] = kind;
        return depth + 1;
    }

    // ---- $ ----

    private void dollar() {
        char next = i + 1 < n ? text.charAt(i + 1) : '\0';
        if (next == '\'') ansiCQuoted();
        else if (next == '"') doubleQuoted(i, i + 1);
        else if (next == '{') bracedParameter();
        else if (next == '(') {
            if (i + 2 < n && text.charAt(i + 2) == '(') { arithmetic++; i += 3; commandStart = false; }
            else { substitutions++; i += 2; commandStart = true; }
        } else if (Character.isLetter(next) || next == '_') {
            int end = i + 2;
            while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
            add(i, end, "type");
            i = end;
            tokenEnded();
        } else if (Character.isDigit(next) || next == '@' || next == '*' || next == '#' || next == '?' || next == '$' || next == '!' || next == '-') {
            add(i, i + 2, "type");
            i += 2;
            tokenEnded();
        } else {
            i++;
            tokenEnded();
        }
    }

    private void ansiCQuoted() {
        int end = lineEnd(i);
        int j = i + 2;
        while (j < end && text.charAt(j) != '\'') j += text.charAt(j) == '\\' ? 2 : 1;
        int stop = j < end ? j + 1 : end;
        add(i, stop, "string");
        i = stop;
        tokenEnded();
    }

    /** {@code ${...}} up to the first closing brace on the same line; without one, the {@code $} is left plain. */
    private void bracedParameter() {
        int close = closeBrace.find(i + 2);
        int nl = newline.find(i + 2);
        if (close < 0 || nl >= 0 && nl < close) { i++; tokenEnded(); return; }
        add(i, close + 1, "type");
        i = close + 1;
        tokenEnded();
    }

    // ---- punctuation that changes what is a command ----

    private void openParen() {
        if (i + 1 < n && text.charAt(i + 1) == '(') { arithmetic++; i += 2; commandStart = false; }
        else { i++; commandStart = true; }
    }

    private void closeParen() {
        if (arithmetic > 0 && i + 1 < n && text.charAt(i + 1) == ')') { arithmetic--; i += 2; commandStart = false; }
        else if (substitutions > 0) { substitutions--; i++; commandStart = false; }
        else { i++; commandStart = true; }
    }

    private void ampersand() {
        boolean redirect = i > 0 && (text.charAt(i - 1) == '>' || text.charAt(i - 1) == '<') || i + 1 < n && text.charAt(i + 1) == '>'; // >&2, 2>&1, &>file
        i++;
        if (!redirect) { commandStart = true; awaitingIn = false; }
    }

    private void lessThan() {
        if (text.startsWith("<<<", i)) i += 3; // a here-string
        else if (arithmetic == 0 && text.startsWith("<<", i) && heredocStart()) return;
        else i++;
    }

    // ---- here-documents ----

    /** Handles {@code <<WORD}, {@code <<-WORD}, {@code <<'WORD'} at the cursor; false (nothing consumed) if what follows is not a delimiter. */
    private boolean heredocStart() {
        int j = i + 2;
        boolean stripTabs = j < n && text.charAt(j) == '-';
        if (stripTabs) j++;
        while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) j++;
        if (j >= n) return false;
        char first = text.charAt(j);
        int end;
        String delimiter;
        if (first == '\'' || first == '"') {
            int close = (first == '\'' ? singleQuote : doubleQuote).find(j + 1);
            int nl = newline.find(j + 1);
            if (close < 0 || close == j + 1 || nl >= 0 && nl < close) return false;
            delimiter = text.substring(j + 1, close);
            end = close + 1;
        } else {
            end = j;
            while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_' || text.charAt(end) == '-' || text.charAt(end) == '.')) end++;
            if (!(Character.isLetter(first) || first == '_')) return false; // `1<<3` is a shift
            delimiter = text.substring(j, end);
        }
        add(j, end, "keyword");
        pending.add(new Heredoc(delimiter, stripTabs));
        i = end;
        return true;
    }

    /** Colours the lines after a line break that belong to the here-documents opened on the line before, up to each delimiter line. */
    private void heredocBodies() {
        for (Heredoc heredoc : pending) {
            while (i < n) {
                int lineStart = i;
                int end = lineEnd(lineStart);
                int content = lineStart;
                if (heredoc.stripTabs()) while (content < end && text.charAt(content) == '\t') content++;
                int nl = newline.find(lineStart);
                i = nl < 0 ? n : nl + 1;
                if (end - content == heredoc.delimiter().length() && text.startsWith(heredoc.delimiter(), content)) {
                    add(content, end, "keyword");
                    break;
                }
                add(lineStart, end, "string");
            }
        }
        pending.clear();
    }

    // ---- words ----

    private void word() {
        int start = i;
        boolean atStart = atWordStart(start);
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') { i = Math.min(n, i + 2); continue; }
            if (endsPlainWord(c)) break;
            i++;
        }
        if (i == start) i++;
        if (atStart) classify(text.substring(start, i), start);
    }

    private void classify(String word, int start) {
        int assignedName = assignmentNameEnd(word);
        if (assignedName > 0) { // NAME=value: the name is a variable, and a command may still follow
            add(start, start + assignedName, "type");
            inAssignment = true;
            return;
        }
        if (word.equals("{")) { // a brace group (or a function body) opens, and a command follows
            commandStart = true;
            return;
        }
        if (commandStart) {
            if (RESERVED.contains(word)) {
                add(start, i, "keyword");
                commandStart = COMMAND_FOLLOWS.contains(word);
                awaitingIn = word.equals("for") || word.equals("case") || word.equals("select");
            } else if (word.equals("!")) {
                return; // a command follows, so the position is kept
            } else if (word.equals("[[")) {
                add(start, i, "keyword");
                commandStart = false;
            } else {
                if (BUILTINS.contains(word)) add(start, i, "annotation");
                commandStart = false;
            }
            return;
        }
        if (awaitingIn && word.equals("in")) { add(start, i, "keyword"); awaitingIn = false; }
        else if (word.equals("[[") || word.equals("]]")) add(start, i, "keyword");
        else if (isDigits(word)) add(start, i, "number");
    }

    /** The length of the variable name of an assignment word ({@code NAME=}, {@code NAME+=}), or 0 if the word is not one. */
    private static int assignmentNameEnd(String word) {
        int k = 0;
        if (word.isEmpty() || !(Character.isLetter(word.charAt(0)) || word.charAt(0) == '_')) return 0;
        while (k < word.length() && (Character.isLetterOrDigit(word.charAt(k)) || word.charAt(k) == '_')) k++;
        int name = k;
        if (k < word.length() && word.charAt(k) == '+') k++;
        return k < word.length() && word.charAt(k) == '=' ? name : 0;
    }

    private static boolean isDigits(String word) {
        for (int k = 0; k < word.length(); k++) if (word.charAt(k) < '0' || word.charAt(k) > '9') return false;
        return !word.isEmpty();
    }
}
