package com.javelin.language;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain value types for the parts of the Language Server Protocol the editor uses, with parsers for server replies. */
public final class Lsp {
    private Lsp() {}

    /** Zero-based line and UTF-16 column, which matches Java string offsets. */
    public record Position(int line, int character) {
        Map<String, Object> toJson() { return Json.obj("line", line, "character", character); }
        static Position from(Object json) { return new Position(Json.integer(json, "line", 0), Json.integer(json, "character", 0)); }
    }

    public record Range(Position start, Position end) {
        Map<String, Object> toJson() { return Json.obj("start", start.toJson(), "end", end.toJson()); }
        static Range from(Object json) {
            Object s = Json.get(json, "start"), e = Json.get(json, "end");
            if (s == null || e == null) return null;
            return new Range(Position.from(s), Position.from(e));
        }
    }

    public record Location(URI uri, Range range) {}

    public record TextEdit(Range range, String newText) {}

    /** Edits grouped by document. {@code unsupported} is true when the server also asked for file create/rename/delete. */
    public record WorkspaceEdit(Map<URI, List<TextEdit>> changes, boolean unsupported) {}

    /** One entry of a document outline, flattened from the server's tree. {@code depth} is the nesting level; {@code range} is the name's location. */
    public record Symbol(String name, String detail, int kind, Range range, int depth) {
        private static final String[] KINDS = {"", "file", "module", "namespace", "package", "class", "method", "property", "field", "constructor", "enum",
            "interface", "function", "variable", "constant", "string", "number", "boolean", "array", "object", "key", "null", "enum member", "struct",
            "event", "operator", "type parameter"};
        public String kindName() { return kind > 0 && kind < KINDS.length ? KINDS[kind] : "symbol"; }
    }

    public record Diagnostic(Range range, int severity, String message, String source) {
        public static final int ERROR = 1, WARNING = 2, INFORMATION = 3, HINT = 4;
    }

    /** A completion candidate. {@code edit} is non-null when the server specified exactly what to replace. */
    public record CompletionItem(String label, String detail, int kind, String insertText, String filterText, TextEdit edit, boolean snippet) {
        public String filter() { return filterText != null ? filterText : label; }
        /** The text to insert, with snippet placeholders flattened because the editor has no snippet mode. */
        public String text() {
            String raw = edit != null ? edit.newText() : insertText != null ? insertText : label;
            return snippet ? flattenSnippet(raw) : raw;
        }
    }

    public static Map<String, Object> textDocument(URI uri) { return Json.obj("uri", uri.toString()); }

    public static Map<String, Object> positionParams(URI uri, Position position) {
        return Json.obj("textDocument", textDocument(uri), "position", position.toJson());
    }

    public static List<Location> locations(Object result) {
        List<Location> out = new ArrayList<>();
        if (result == null) return out;
        List<Object> items = Json.asArray(result);
        if (items == null) items = List.of(result);
        for (Object item : items) {
            String uri = Json.string(item, "targetUri") != null ? Json.string(item, "targetUri") : Json.string(item, "uri"); // LocationLink or Location
            Object rangeJson = Json.get(item, "targetSelectionRange") != null ? Json.get(item, "targetSelectionRange") : Json.get(item, "range");
            Range range = Range.from(rangeJson);
            if (uri == null || range == null) continue;
            try { out.add(new Location(URI.create(uri), range)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    public static List<CompletionItem> completions(Object result) {
        List<Object> items = Json.asArray(result);
        if (items == null) items = Json.asArray(Json.get(result, "items")); // CompletionList
        List<CompletionItem> out = new ArrayList<>();
        if (items == null) return out;
        for (Object item : items) {
            String label = Json.string(item, "label");
            if (label == null) continue;
            Object editJson = Json.get(item, "textEdit");
            TextEdit edit = null;
            if (editJson != null) {
                Range range = Range.from(Json.get(editJson, "range") != null ? Json.get(editJson, "range") : Json.get(editJson, "insert")); // TextEdit or InsertReplaceEdit
                String newText = Json.string(editJson, "newText");
                if (range != null && newText != null) edit = new TextEdit(range, newText);
            }
            out.add(new CompletionItem(label, Json.string(item, "detail"), Json.integer(item, "kind", 0), Json.string(item, "insertText"),
                Json.string(item, "filterText"), edit, Json.integer(item, "insertTextFormat", 1) == 2));
        }
        return out;
    }

    /** Flattens Hover contents (MarkupContent, MarkedString, or an array of them) to readable text. */
    public static String hoverText(Object result) {
        if (result == null) return "";
        return stripMarkdown(markedText(Json.get(result, "contents"))).trim();
    }

    private static String markedText(Object contents) {
        if (contents == null) return "";
        if (contents instanceof String s) return s;
        List<Object> list = Json.asArray(contents);
        if (list != null) {
            StringBuilder sb = new StringBuilder();
            for (Object part : list) { String text = markedText(part); if (!text.isBlank()) { if (sb.length() > 0) sb.append("\n\n"); sb.append(text); } }
            return sb.toString();
        }
        String value = Json.string(contents, "value");
        return value == null ? "" : value;
    }

    private static String stripMarkdown(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) continue;
            sb.append(line.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")).append('\n');
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n");
    }

    public static WorkspaceEdit workspaceEdit(Object json) {
        Map<URI, List<TextEdit>> changes = new java.util.LinkedHashMap<>();
        boolean unsupported = false;
        Object plain = Json.get(json, "changes");
        if (Json.asObject(plain) != null) for (Map.Entry<String, Object> e : Json.asObject(plain).entrySet()) addEdits(changes, e.getKey(), e.getValue());
        List<Object> documentChanges = Json.asArray(Json.get(json, "documentChanges"));
        if (documentChanges != null) for (Object change : documentChanges) {
            if (Json.get(change, "kind") != null) { unsupported = true; continue; } // create / rename / delete resource operations
            String uri = Json.string(Json.get(change, "textDocument"), "uri");
            if (uri != null) addEdits(changes, uri, Json.get(change, "edits"));
        }
        return new WorkspaceEdit(changes, unsupported);
    }

    private static void addEdits(Map<URI, List<TextEdit>> changes, String uri, Object edits) {
        List<Object> list = Json.asArray(edits);
        if (list == null) return;
        URI key;
        try { key = URI.create(uri); } catch (IllegalArgumentException ex) { return; }
        for (Object edit : list) {
            Range range = Range.from(Json.get(edit, "range")); String text = Json.string(edit, "newText");
            if (range != null && text != null) changes.computeIfAbsent(key, k -> new ArrayList<>()).add(new TextEdit(range, text));
        }
    }

    public static List<Diagnostic> diagnostics(Object list) {
        List<Diagnostic> out = new ArrayList<>();
        List<Object> items = Json.asArray(list);
        if (items == null) return out;
        for (Object item : items) {
            Range range = Range.from(Json.get(item, "range")); String message = Json.string(item, "message");
            if (range != null && message != null) out.add(new Diagnostic(range, Json.integer(item, "severity", Diagnostic.ERROR), message, Json.string(item, "source")));
        }
        return out;
    }

    /**
     * Removes snippet syntax, leaving the text a user would have after accepting every default: {@code $1} and
     * {@code ${1}} vanish, {@code ${1:name}} becomes {@code name} (placeholders may nest), {@code ${1|a,b|}} becomes
     * the first choice, {@code ${VAR:default}} becomes the default, and backslash escapes ({@code \$}, {@code \}},
     * {@code \\}) become the literal character. Anything malformed is kept as written rather than guessed at.
     */
    static String flattenSnippet(String snippet) {
        StringBuilder out = new StringBuilder();
        flatten(snippet, 0, snippet.length(), out);
        return out.toString();
    }

    private static void flatten(String s, int from, int to, StringBuilder out) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to && "$}\\,|".indexOf(s.charAt(i + 1)) >= 0) { out.append(s.charAt(i + 1)); i += 2; continue; }
            if (c != '$' || i + 1 >= to) { out.append(c); i++; continue; }
            char next = s.charAt(i + 1);
            if (next == '{') {
                int close = matchingBrace(s, i + 1, to);
                if (close < 0) { out.append(c); i++; continue; }
                flattenBraced(s, i + 2, close, out);
                i = close + 1;
            } else if (Character.isDigit(next)) {
                i += 1;
                while (i < to && Character.isDigit(s.charAt(i))) i++;
            } else if (Character.isLetter(next) || next == '_') {
                i += 1;
                while (i < to && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            } else { out.append(c); i++; }
        }
    }

    /** Index of the brace closing the one at {@code open}, honouring escapes and nesting; -1 when it never closes. */
    private static int matchingBrace(String s, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static void flattenBraced(String s, int from, int to, StringBuilder out) {
        int i = from;
        while (i < to && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
        if (i == from) { out.append("${"); flatten(s, from, to, out); out.append('}'); return; } // not a tabstop or variable: keep it
        if (i == to) return; // ${1} or ${NAME}
        char kind = s.charAt(i);
        if (kind == ':') flatten(s, i + 1, to, out);
        else if (kind == '|' && s.charAt(to - 1) == '|') {
            int end = i + 1;
            while (end < to - 1 && !(s.charAt(end) == ',' && s.charAt(end - 1) != '\\')) end++;
            flatten(s, i + 1, Math.min(end, to - 1), out);
        } else { out.append("${"); flatten(s, from, to, out); out.append('}'); }
    }

    /**
     * Flattens signature help to readable text: the active signature, a line naming the active parameter, and any documentation.
     * Returns an empty string when the server has no signature for the position.
     */
    public static String signatureText(Object result) {
        List<Object> signatures = Json.asArray(Json.get(result, "signatures"));
        if (signatures == null || signatures.isEmpty()) return "";
        int index = Math.max(0, Math.min(Json.integer(result, "activeSignature", 0), signatures.size() - 1));
        Object signature = signatures.get(index);
        String label = Json.string(signature, "label");
        if (label == null) return "";
        StringBuilder sb = new StringBuilder(label);
        List<Object> parameters = Json.asArray(Json.get(signature, "parameters"));
        int active = Json.get(signature, "activeParameter") instanceof Number ? Json.integer(signature, "activeParameter", 0) : Json.integer(result, "activeParameter", 0);
        if (parameters != null && active >= 0 && active < parameters.size()) {
            String name = parameterLabel(Json.get(parameters.get(active), "label"), label);
            if (!name.isBlank()) sb.append("\n\nParameter: ").append(name);
            String doc = stripMarkdown(markedText(Json.get(parameters.get(active), "documentation"))).trim();
            if (!doc.isEmpty()) sb.append("\n").append(doc);
        }
        String doc = stripMarkdown(markedText(Json.get(signature, "documentation"))).trim();
        if (!doc.isEmpty()) sb.append("\n\n").append(doc);
        if (signatures.size() > 1) sb.append("\n\n(overload ").append(index + 1).append(" of ").append(signatures.size()).append(")");
        return sb.toString();
    }

    /** A parameter label is either its text or a [start, end) offset pair into the signature label. */
    private static String parameterLabel(Object label, String signatureLabel) {
        if (label instanceof String s) return s;
        List<Object> span = Json.asArray(label);
        if (span != null && span.size() == 2 && span.get(0) instanceof Number a && span.get(1) instanceof Number b) {
            int start = a.intValue(), end = b.intValue();
            if (start >= 0 && start <= end && end <= signatureLabel.length()) return signatureLabel.substring(start, end);
        }
        return "";
    }

    /** Parses a document-symbol reply: either a DocumentSymbol tree or a flat SymbolInformation list. Depth-first order. */
    public static List<Symbol> symbols(Object result) {
        List<Symbol> out = new ArrayList<>();
        List<Object> items = Json.asArray(result);
        if (items != null) addSymbols(out, items, 0);
        return out;
    }

    private static void addSymbols(List<Symbol> out, List<Object> items, int depth) {
        for (Object item : items) {
            String name = Json.string(item, "name");
            if (name == null) continue;
            // DocumentSymbol has selectionRange (the name itself); SymbolInformation nests the range in location.
            Range range = Range.from(Json.get(item, "selectionRange") != null ? Json.get(item, "selectionRange")
                : Json.get(item, "range") != null ? Json.get(item, "range") : Json.get(Json.get(item, "location"), "range"));
            if (range != null) out.add(new Symbol(name, Json.string(item, "detail"), Json.integer(item, "kind", 0), range, depth));
            List<Object> children = Json.asArray(Json.get(item, "children"));
            if (children != null && depth < 32) addSymbols(out, children, depth + 1);
        }
    }

    /** Parses the plain TextEdit list returned by formatting requests. Null (nothing to change) becomes an empty list. */
    public static List<TextEdit> textEdits(Object result) {
        List<TextEdit> out = new ArrayList<>();
        List<Object> items = Json.asArray(result);
        if (items == null) return out;
        for (Object edit : items) {
            Range range = Range.from(Json.get(edit, "range")); String text = Json.string(edit, "newText");
            if (range != null && text != null) out.add(new TextEdit(range, text));
        }
        return out;
    }
}
