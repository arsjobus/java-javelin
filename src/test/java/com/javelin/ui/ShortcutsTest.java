package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Keybindings;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

/**
 * Only the pure parts of {@link Shortcuts} are tested: parsing a binding into a combination and
 * recording one from a key event. Neither needs the JavaFX toolkit to be running (matching a
 * combination against an event would).
 */
class ShortcutsTest {
  private static KeyEvent press(
      KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {
    return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta);
  }

  @Test
  void defaultSaveBindingParsesToThePlatformShortcutKeyPlusS() {
    KeyCodeCombination save = (KeyCodeCombination) Shortcuts.parse(Keybindings.SAVE_DEFAULT);
    assertNotNull(save);
    assertEquals(KeyCode.S, save.getCode());
    assertEquals(KeyCombination.ModifierValue.DOWN, save.getShortcut());
    assertEquals(
        KeyCombination.ModifierValue.UP,
        save.getControl(),
        "Shortcut is resolved by JavaFX per platform");
    assertEquals(KeyCombination.ModifierValue.UP, save.getMeta());
    assertEquals(KeyCombination.ModifierValue.UP, save.getShift());
  }

  @Test
  void defaultSaveAsBindingAddsShift() {
    KeyCodeCombination saveAs = (KeyCodeCombination) Shortcuts.parse(Keybindings.SAVE_AS_DEFAULT);
    assertNotNull(saveAs);
    assertEquals(KeyCode.S, saveAs.getCode());
    assertEquals(KeyCombination.ModifierValue.DOWN, saveAs.getShortcut());
    assertEquals(KeyCombination.ModifierValue.DOWN, saveAs.getShift());
  }

  @Test
  void cmdSpellingsParseToTheMetaKey() {
    for (String spelling : new String[] {"Cmd+S", "Command+S", "Meta+S", "meta+s"}) {
      KeyCodeCombination combination = (KeyCodeCombination) Shortcuts.parse(spelling);
      assertNotNull(combination, spelling);
      assertEquals(KeyCode.S, combination.getCode(), spelling);
      assertEquals(KeyCombination.ModifierValue.DOWN, combination.getMeta(), spelling);
      assertEquals(KeyCombination.ModifierValue.UP, combination.getControl(), spelling);
    }
  }

  @Test
  void ctrlSStillParsesToTheControlKey() {
    KeyCodeCombination combination = (KeyCodeCombination) Shortcuts.parse("Ctrl+S");
    assertNotNull(combination);
    assertEquals(KeyCombination.ModifierValue.DOWN, combination.getControl());
    assertEquals(KeyCombination.ModifierValue.UP, combination.getMeta());
  }

  @Test
  void emptyAndUnknownBindingsParseToNull() {
    assertNull(Shortcuts.parse(""));
    assertNull(Shortcuts.parse("none"));
    assertNull(Shortcuts.parse("Cmd+Nonsense"));
    assertNull(Shortcuts.parse("Cmd"));
  }

  @Test
  void recordingCmdSInTheSettingsDialogGivesMetaS() {
    assertEquals("Meta+S", Shortcuts.fromEvent(press(KeyCode.S, false, false, false, true)));
    assertEquals("Shift+Meta+S", Shortcuts.fromEvent(press(KeyCode.S, true, false, false, true)));
  }

  @Test
  void aRecordedCmdSEqualsTheDefaultSaveOnMac() {
    String recorded = Shortcuts.fromEvent(press(KeyCode.S, false, false, false, true));
    assertEquals(
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, true),
        Keybindings.normalize(recorded, true));
  }

  @Test
  void aRecordedCtrlSEqualsTheDefaultSaveOffMac() {
    String recorded = Shortcuts.fromEvent(press(KeyCode.S, false, true, false, false));
    assertEquals("Ctrl+S", recorded);
    assertEquals(
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, false),
        Keybindings.normalize(recorded, false));
  }

  @Test
  void aPlainLetterCannotBeRecordedAsAShortcut() {
    assertNull(Shortcuts.fromEvent(press(KeyCode.S, false, false, false, false)));
    assertNull(
        Shortcuts.fromEvent(press(KeyCode.META, false, false, false, true)),
        "a lone modifier is not a shortcut");
  }
}
