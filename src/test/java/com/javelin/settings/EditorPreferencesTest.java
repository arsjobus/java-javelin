package com.javelin.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorPreferencesTest {
    @TempDir Path dir;
    private Settings settings;

    @BeforeEach void setUp() { settings = new Settings(dir.resolve("settings.conf")); }

    private void set(String key, String value) { settings.put(Scope.USER, key, value); }

    @Test void defaults() {
        EditorPreferences p = EditorPreferences.from(settings);
        assertEquals(new EditorPreferences("Monospaced", 14, 4, true, false, true, true), p);
        assertEquals("    ", p.indentUnit());
        assertTrue(EditorPreferences.validate(settings).isEmpty());
    }

    @Test void valuesAreReadAndClamped() {
        set("editor.fontSize", "100"); set("editor.tabSize", "0"); set("editor.insertSpaces", "false"); set("editor.wordWrap", "TRUE");
        set("editor.lineNumbers", "false"); set("editor.autoIndent", "false");
        EditorPreferences p = EditorPreferences.from(settings);
        assertEquals(48, p.fontSize());
        assertEquals(1, p.tabSize());
        assertFalse(p.insertSpaces());
        assertTrue(p.wordWrap());
        assertFalse(p.lineNumbers());
        assertFalse(p.autoIndent());
        assertEquals("\t", p.indentUnit());
        set("editor.fontSize", "-5"); set("editor.tabSize", "99");
        p = EditorPreferences.from(settings);
        assertEquals(8, p.fontSize());
        assertEquals(16, p.tabSize());
    }

    @Test void validationExplainsEachKindOfProblem() {
        set("editor.fontSize", "huge"); set("editor.tabSize", "40"); set("editor.wordWrap", "yes"); set("editor.autoIndent", "TRUE");
        List<String> problems = EditorPreferences.validate(settings);
        assertEquals(3, problems.size());
        assertTrue(problems.get(0).contains("editor.fontSize") && problems.get(0).contains("not a whole number"));
        assertTrue(problems.get(1).contains("editor.tabSize") && problems.get(1).contains("outside 1-16"));
        assertTrue(problems.get(2).contains("editor.wordWrap") && problems.get(2).contains("not true or false"));
        assertEquals(EditorPreferences.DEFAULT_FONT_SIZE, EditorPreferences.from(settings).fontSize());
    }

    @Test void fontNamesCannotBreakOutOfTheQuotedCssValue() {
        set("editor.fontFamily", "Fira Code'; -fx-background-color: red; /*");
        String style = EditorPreferences.from(settings).style(0);
        assertFalse(style.contains(";-") || style.contains("red;"), style);
        assertEquals(1, style.chars().filter(c -> c == '\'').count() / 2);
        assertEquals("Monospaced", EditorPreferences.cleanFont("';\"{}"));
        assertEquals("Fira-Code_1.0", EditorPreferences.cleanFont("  Fira-Code_1.0  "));
        assertEquals("Menlo", EditorPreferences.cleanFont("Menlo\n"));
    }

    @Test void styleAppliesZoomWithinSaneBounds() {
        EditorPreferences p = EditorPreferences.from(settings);
        assertEquals("-fx-font-family: 'Monospaced'; -fx-font-size: 16px; -fx-tab-size: 4;", p.style(2));
        assertTrue(p.style(-100).contains("6px"));
        assertTrue(p.style(1000).contains("72px"));
    }
}
