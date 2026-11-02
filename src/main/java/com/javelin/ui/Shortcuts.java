package com.javelin.ui;

import com.javelin.settings.Keybindings;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts between the shortcut strings stored in settings ({@code Ctrl+Shift+F}, {@code F12},
 * {@code Ctrl+BACK_QUOTE}) and JavaFX key combinations. Key names are the {@link KeyCode} constant names,
 * case-insensitive, with a space accepted for an underscore; a single digit means the digit key.
 */
public final class Shortcuts {
    private Shortcuts() {}

    /** The combination for a binding, or null when the binding is empty, "none", or not understood. */
    public static KeyCombination parse(String binding) {
        if (Keybindings.normalize(binding).isEmpty()) return null;
        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        KeyCode code = null;
        for (String raw : binding.split("\\+")) {
            String token = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
            switch (token) {
                case "CTRL", "CONTROL" -> modifiers.add(KeyCombination.CONTROL_DOWN);
                case "SHIFT" -> modifiers.add(KeyCombination.SHIFT_DOWN);
                case "ALT", "OPTION" -> modifiers.add(KeyCombination.ALT_DOWN);
                case "META", "CMD", "COMMAND" -> modifiers.add(KeyCombination.META_DOWN);
                case "SHORTCUT" -> modifiers.add(KeyCombination.SHORTCUT_DOWN);
                default -> {
                    if (code != null) return null; // two non-modifier keys
                    try {
                        code = KeyCode.valueOf(token.length() == 1 && Character.isDigit(token.charAt(0)) ? "DIGIT" + token : token);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
            }
        }
        if (code == null) return null;
        return new KeyCodeCombination(code, modifiers.toArray(new KeyCombination.Modifier[0]));
    }

    /** How a binding reads in a menu or table; empty when there is none or it is not understood. */
    public static String display(String binding) {
        KeyCombination combination = parse(binding);
        return combination == null ? "" : combination.getDisplayText();
    }

    /**
     * The binding string for a key press, or null when the press cannot be a shortcut: a lone modifier key, or a
     * key that would hijack typing (anything other than a function key needs Ctrl, Alt or Meta).
     */
    public static String fromEvent(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) return null;
        boolean command = event.isControlDown() || event.isAltDown() || event.isMetaDown();
        if (!command && !code.isFunctionKey()) return null;
        StringBuilder text = new StringBuilder();
        if (event.isControlDown()) text.append("Ctrl+");
        if (event.isShiftDown()) text.append("Shift+");
        if (event.isAltDown()) text.append("Alt+");
        if (event.isMetaDown()) text.append("Meta+");
        return text.append(code.name()).toString();
    }
}
