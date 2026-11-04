package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a model's reply to "change this code" into the text the code should become. A reply may hold edit blocks
 * (SEARCH / REPLACE), a unified diff in a code block, or the whole replacement in one code block; the three are
 * recognised in that order, whatever the prompt asked for, because small models do not always do what they are told.
 * Nothing here touches a file or a window.
 */
public final class ChangeReply {
    private ChangeReply() {}

    public enum Format { EDIT_BLOCKS, DIFF, WHOLE_BLOCK }

    /** {@code code} is the new text of the part that was sent, {@code notes} what was repaired on the way. */
    public record Interpreted(String code, String explanation, int extraBlocks, Format format, List<Finding> notes) {}

    private static final java.util.regex.Pattern REASONING = java.util.regex.Pattern.compile("(?is)<think(?:ing)?>.*?(?:</think(?:ing)?>|\\z)");

    /**
     * The reply without the model's reasoning. Models that think aloud may put it in the answer between {@code <think>}
     * tags, and the code they try out while thinking is not the answer: the first code block must come from after it.
     * A reasoning section that never closes swallows the rest, so such a reply proposes nothing.
     */
    static String withoutReasoning(String reply) { return REASONING.matcher(reply).replaceAll("").strip(); }

    public static Interpreted interpret(String rawReply, String original) throws AiException {
        String reply = withoutReasoning(rawReply);
        if (EditBlocks.looksLike(reply)) {
            EditBlocks.Parsed parsed = EditBlocks.parse(reply);
            return new Interpreted(EditBlocks.apply(original, parsed.edits()), parsed.explanation(), 0, Format.EDIT_BLOCKS, List.of());
        }
        ResponseParser.Parsed block = ResponseParser.parse(reply);
        if (UnifiedPatch.looksLike(block.code()))
            return new Interpreted(UnifiedPatch.apply(original, block.code()), block.explanation(), block.extraBlocks(), Format.DIFF, List.of());
        List<Finding> notes = new ArrayList<>();
        String code = restoreIndentation(original, block.code(), notes);
        return new Interpreted(code, block.explanation(), block.extraBlocks(), Format.WHOLE_BLOCK, notes);
    }

    /**
     * Models very often hand code back without the indentation it had in the file, so it would land flush left in the
     * middle of a method. When the reply's first line has no indentation and the first line that was sent had some, the
     * sent indentation is put in front of every line (the lines keep their indentation relative to each other). A reply
     * that starts with some indentation of its own is left alone, whatever it is.
     */
    static String restoreIndentation(String original, String code, List<Finding> notes) {
        List<String> sent = LineMatch.split(original);
        List<String> reply = LineMatch.split(code);
        String want = LineMatch.firstIndent(sent);
        if (want.isEmpty() || !LineMatch.firstIndent(reply).isEmpty()) return code;
        List<String> fixed = LineMatch.shiftIndent(reply, want, "");
        notes.add(Finding.warning("The reply started with no indentation, so the indentation of the selected code was added to every line."));
        return String.join("\n", fixed);
    }
}
