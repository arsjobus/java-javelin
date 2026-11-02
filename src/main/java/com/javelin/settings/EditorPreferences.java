package com.javelin.settings;

import java.util.ArrayList;
import java.util.List;

/** The editor-behavior settings (keys {@code editor.*}) with their defaults and limits. JavaFX-free. */
public record EditorPreferences(String fontFamily, int fontSize, int tabSize, boolean insertSpaces,
                                boolean wordWrap, boolean lineNumbers, boolean autoIndent) {
    public static final String DEFAULT_FONT = "Monospaced";
    public static final int DEFAULT_FONT_SIZE = 14, MIN_FONT_SIZE = 8, MAX_FONT_SIZE = 48;
    public static final int DEFAULT_TAB_SIZE = 4, MIN_TAB_SIZE = 1, MAX_TAB_SIZE = 16;

    public static EditorPreferences from(Settings settings) {
        return new EditorPreferences(
            cleanFont(settings.get("editor.fontFamily", DEFAULT_FONT)),
            clamp(settings.getInt("editor.fontSize", DEFAULT_FONT_SIZE), MIN_FONT_SIZE, MAX_FONT_SIZE),
            clamp(settings.getInt("editor.tabSize", DEFAULT_TAB_SIZE), MIN_TAB_SIZE, MAX_TAB_SIZE),
            settings.getBoolean("editor.insertSpaces", true),
            settings.getBoolean("editor.wordWrap", false),
            settings.getBoolean("editor.lineNumbers", true),
            settings.getBoolean("editor.autoIndent", true));
    }

    /** Problems with the current {@code editor.*} values, for display; {@link #from} falls back or clamps silently. */
    public static List<String> validate(Settings settings) {
        List<String> problems = new ArrayList<>();
        checkInt(settings, "editor.fontSize", MIN_FONT_SIZE, MAX_FONT_SIZE, problems);
        checkInt(settings, "editor.tabSize", MIN_TAB_SIZE, MAX_TAB_SIZE, problems);
        for (String key : List.of("editor.insertSpaces", "editor.wordWrap", "editor.lineNumbers", "editor.autoIndent")) {
            String raw = settings.get(key, null);
            if (raw != null && !raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false"))
                problems.add(key + ": '" + raw + "' is not true or false; the default is used");
        }
        return problems;
    }

    /** What one indentation step inserts. */
    public String indentUnit() { return insertSpaces ? " ".repeat(tabSize) : "\t"; }

    /** Inline JavaFX CSS for a code area; {@code zoom} is a temporary font-size offset that is not saved. */
    public String style(int zoom) {
        int size = clamp(fontSize + zoom, 6, 72);
        return "-fx-font-family: '" + fontFamily + "'; -fx-font-size: " + size + "px; -fx-tab-size: " + tabSize + ";";
    }

    /** Keeps only characters that cannot break out of the quoted CSS value. */
    static String cleanFont(String name) {
        StringBuilder clean = new StringBuilder();
        for (char c : name.toCharArray()) if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') clean.append(c);
        String result = clean.toString().strip();
        return result.isEmpty() ? DEFAULT_FONT : result;
    }

    private static void checkInt(Settings settings, String key, int min, int max, List<String> problems) {
        String raw = settings.get(key, null);
        if (raw == null) return;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) problems.add(key + ": " + value + " is outside " + min + "-" + max + "; the nearest allowed value is used");
        } catch (NumberFormatException ex) {
            problems.add(key + ": '" + raw + "' is not a whole number; the default is used");
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
