package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the code a model put in its reply. Only a complete, fenced code block is ever taken as code: a reply without
 * one, or one that stops inside the block, produces no proposal, because applying half a file is worse than applying nothing.
 */
public final class ResponseParser {
    private ResponseParser() {}

    /** {@code explanation} is the text around the code block, {@code extraBlocks} the number of further code blocks that were ignored. */
    public record Parsed(String explanation, String code, int extraBlocks) {}

    public static Parsed parse(String reply) throws AiException {
        List<String> lines = new ArrayList<>(List.of(reply.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        int open = -1;
        char fenceChar = 0;
        int fenceLength = 0;
        for (int i = 0; i < lines.size() && open < 0; i++) {
            Fence fence = Fence.opening(lines.get(i));
            if (fence != null) { open = i; fenceChar = fence.character; fenceLength = fence.length; }
        }
        if (open < 0) throw new AiException(AiException.Kind.BAD_RESPONSE, "The reply has no code block, so there is no change to propose. Its text is in the AI panel.");
        int close = -1;
        for (int i = open + 1; i < lines.size() && close < 0; i++) if (Fence.closes(lines.get(i), fenceChar, fenceLength)) close = i;
        if (close < 0) throw new AiException(AiException.Kind.BAD_RESPONSE, "The reply stops inside its code block (it was probably cut off), so nothing is proposed. Its text is in the AI panel.");
        String code = String.join("\n", lines.subList(open + 1, close));
        if (code.isBlank()) throw new AiException(AiException.Kind.BAD_RESPONSE, "The code block in the reply is empty, so there is no change to propose.");
        int extra = 0;
        for (int i = close + 1; i < lines.size(); i++) {
            Fence fence = Fence.opening(lines.get(i));
            if (fence == null) continue;
            extra++;
            int end = i;
            for (int j = i + 1; j < lines.size(); j++) if (Fence.closes(lines.get(j), fence.character, fence.length)) { end = j; break; }
            i = end;
        }
        String before = String.join("\n", lines.subList(0, open)).strip();
        String after = String.join("\n", lines.subList(close + 1, lines.size())).strip();
        String explanation = (before + (before.isEmpty() || after.isEmpty() ? "" : "\n\n") + after).strip();
        return new Parsed(explanation, code, extra);
    }

    /** The code with the final line break of the original kept or dropped, so replacing a selection does not add or remove a line. */
    public static String matchTrailingNewline(String original, String code) {
        String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        return original.endsWith("\n") ? body + "\n" : body;
    }

    /** The code with Windows line endings when the text it replaces has them, so a replaced selection does not mix the two. */
    public static String matchLineEndings(String original, String code) {
        String plain = code.replace("\r\n", "\n");
        return original.contains("\r\n") ? plain.replace("\n", "\r\n") : plain;
    }

    private record Fence(char character, int length) {
        /** A line that starts a fenced block: up to three spaces, three or more backticks or tildes, then an optional word. */
        static Fence opening(String line) {
            String t = stripIndent(line);
            if (t == null || t.isEmpty()) return null;
            char c = t.charAt(0);
            if (c != '`' && c != '~') return null;
            int n = 0;
            while (n < t.length() && t.charAt(n) == c) n++;
            if (n < 3) return null;
            if (c == '`' && t.indexOf('`', n) >= 0) return null; // ```inline``` is not a fence
            return new Fence(c, n);
        }

        static boolean closes(String line, char character, int length) {
            String t = stripIndent(line);
            if (t == null) return false;
            t = t.stripTrailing();
            if (t.length() < length) return false;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) != character) return false;
            return true;
        }

        private static String stripIndent(String line) {
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
            return spaces > 3 ? null : line.substring(spaces);
        }
    }
}
