package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.DefaultShortcuts;
import com.javelin.settings.Keybindings;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

/**
 * The default shortcuts must all be understood by the UI, and Cmd must be what JavaFX resolves them
 * to on macOS.
 */
class DefaultShortcutsParseTest {
  private static KeyCodeCombination parse(String binding) {
    return (KeyCodeCombination) Shortcuts.parse(binding);
  }

  @Test
  void everyDefaultParses() {
    DefaultShortcuts.ALL.forEach(
        (id, binding) -> assertNotNull(Shortcuts.parse(binding), id + ": " + binding));
  }

  @Test
  void commandKeyDefaultsUseJavaFxShortcutModifierNotCtrlOrMeta() {
    DefaultShortcuts.ALL.forEach(
        (id, binding) -> {
          if (!binding.startsWith("Shortcut+")) return;
          KeyCodeCombination combination = parse(binding);
          assertEquals(KeyCombination.ModifierValue.DOWN, combination.getShortcut(), id);
          assertEquals(
              KeyCombination.ModifierValue.UP,
              combination.getControl(),
              id + " must not be hard-wired to Ctrl");
          assertEquals(
              KeyCombination.ModifierValue.UP,
              combination.getMeta(),
              id + " must not be hard-wired to Meta");
        });
  }

  @Test
  void keysAreTheExpectedOnes() {
    assertEquals(KeyCode.F, parse(DefaultShortcuts.SEARCH).getCode());
    assertEquals(KeyCode.BACK_QUOTE, parse(DefaultShortcuts.TERMINAL).getCode());
    assertEquals(KeyCode.DIGIT0, parse(DefaultShortcuts.ZOOM_RESET).getCode());
    assertEquals(KeyCode.COMMA, parse(DefaultShortcuts.SETTINGS).getCode());
    assertEquals(KeyCode.PERIOD, parse(DefaultShortcuts.STOP_ALL).getCode());
    assertEquals(KeyCombination.ModifierValue.DOWN, parse(DefaultShortcuts.FORMAT).getShift());
    assertEquals(
        KeyCombination.ModifierValue.DOWN, parse(DefaultShortcuts.NEW_TERMINAL).getShift());
  }

  @Test
  void functionKeyDefaultsHaveNoCommandModifier() {
    for (String binding :
        new String[] {
          DefaultShortcuts.FIND_NEXT,
          DefaultShortcuts.GO_TO_DEFINITION,
          DefaultShortcuts.RUN_COMMAND
        }) {
      KeyCodeCombination combination = parse(binding);
      assertEquals(KeyCombination.ModifierValue.UP, combination.getShortcut(), binding);
      assertEquals(KeyCombination.ModifierValue.UP, combination.getControl(), binding);
      assertEquals(KeyCombination.ModifierValue.UP, combination.getMeta(), binding);
    }
  }

  @Test
  void pressingCmdPlusTheKeyIsRecordedAsTheDefaultOnMac() {
    DefaultShortcuts.ALL.forEach(
        (id, binding) -> {
          if (!binding.startsWith("Shortcut+")) return;
          KeyCodeCombination combination = parse(binding);
          boolean shift = combination.getShift() == KeyCombination.ModifierValue.DOWN;
          KeyEvent press =
              new KeyEvent(
                  KeyEvent.KEY_PRESSED, "", "", combination.getCode(), shift, false, false, true);
          String recorded = Shortcuts.fromEvent(press);
          assertNotNull(recorded, id);
          assertEquals(
              Keybindings.normalize(binding, true), Keybindings.normalize(recorded, true), id);
        });
  }

  @Test
  void pressingCtrlPlusTheKeyIsRecordedAsTheDefaultOffMac() {
    DefaultShortcuts.ALL.forEach(
        (id, binding) -> {
          if (!binding.startsWith("Shortcut+")) return;
          KeyCodeCombination combination = parse(binding);
          boolean shift = combination.getShift() == KeyCombination.ModifierValue.DOWN;
          KeyEvent press =
              new KeyEvent(
                  KeyEvent.KEY_PRESSED, "", "", combination.getCode(), shift, true, false, false);
          String recorded = Shortcuts.fromEvent(press);
          assertNotNull(recorded, id);
          assertEquals(
              Keybindings.normalize(binding, false), Keybindings.normalize(recorded, false), id);
        });
  }

  @Test
  void displayTextOfADefaultIsNotEmpty() {
    DefaultShortcuts.ALL.forEach(
        (id, binding) -> assertFalse(Shortcuts.display(binding).isEmpty(), id));
  }
}
