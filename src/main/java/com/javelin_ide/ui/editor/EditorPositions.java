package com.javelin_ide.ui.editor;

import com.javelin_ide.language.Lsp;
import org.fxmisc.richtext.CodeArea;

/** Conversions between language-server positions and offsets in an editor. */
public final class EditorPositions {
    private EditorPositions() {}

    /** The offset of a server position, clamped to the document so a stale position never throws. */
    public static int offsetOf(CodeArea a, Lsp.Position position) {
        int line = Math.max(0, Math.min(position.line(), a.getParagraphs().size() - 1));
        int column = Math.max(0, Math.min(position.character(), a.getParagraphLength(line)));
        return a.getAbsolutePosition(line, column);
    }
}
