package com.javelin_ide.editor;

/** Finding the identifier under the caret, for hover, go-to-definition, references and rename. JavaFX-free. */
public final class Symbols {
    private Symbols() {}

    /** The caret, nudged left when it sits just after an identifier so that identifier counts as the symbol under it. */
    public static int symbolOffset(String text, int caret) {
        boolean onWord = caret < text.length() && Character.isJavaIdentifierPart(text.charAt(caret));
        if (!onWord && caret > 0 && Character.isJavaIdentifierPart(text.charAt(caret - 1))) return caret - 1;
        return caret;
    }

    /** The identifier that contains {@code offset}, or an empty string when {@code offset} is not on one. */
    public static String wordAt(String text, int offset) {
        if (offset < 0 || offset >= text.length() || !Character.isJavaIdentifierPart(text.charAt(offset))) return "";
        int start = offset;
        int end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return text.substring(start, end);
    }
}
