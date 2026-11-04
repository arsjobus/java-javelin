package com.javelin.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File > Save must have a shortcut out of the box, and it must be Cmd+S on macOS and Ctrl+S
 * elsewhere.
 */
class SaveShortcutTest {
  @TempDir Path dir;

  private Keybindings keys(boolean mac) {
    Keybindings keys = new Keybindings(new Settings(dir.resolve("settings.conf")), mac);
    // the same registrations App makes for the two save actions
    keys.register("file.save", "File > Save", Keybindings.SAVE_DEFAULT);
    keys.register("file.saveAs", "File > Save As", Keybindings.SAVE_AS_DEFAULT);
    return keys;
  }

  @Test
  void saveAndSaveAsHaveDefaultShortcuts() {
    assertEquals("Shortcut+S", Keybindings.SAVE_DEFAULT);
    assertEquals("Shortcut+Shift+S", Keybindings.SAVE_AS_DEFAULT);
    Keybindings keys = keys(true);
    assertEquals("Shortcut+S", keys.binding("file.save"), "Save was unbound before the fix");
    assertEquals("Shortcut+Shift+S", keys.binding("file.saveAs"));
  }

  @Test
  void onMacTheSaveShortcutIsCmdS() {
    assertEquals("META+S", Keybindings.normalize(Keybindings.SAVE_DEFAULT, true));
    assertEquals(
        Keybindings.normalize("Cmd+S", true),
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, true));
    assertEquals(
        Keybindings.normalize("Command+S", true),
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, true));
    assertEquals("SHIFT+META+S", Keybindings.normalize(Keybindings.SAVE_AS_DEFAULT, true));
  }

  @Test
  void offMacTheSaveShortcutIsCtrlS() {
    assertEquals("CTRL+S", Keybindings.normalize(Keybindings.SAVE_DEFAULT, false));
    assertEquals(
        Keybindings.normalize("Ctrl+S", false),
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, false));
    assertEquals("CTRL+SHIFT+S", Keybindings.normalize(Keybindings.SAVE_AS_DEFAULT, false));
  }

  @Test
  void shortcutIsKeptAsWrittenWithoutAPlatform() {
    assertEquals("SHORTCUT+S", Keybindings.normalize("Shortcut+S"));
    assertEquals("SHORTCUT+S", Keybindings.normalize("Shortcut+S", null));
    assertEquals("CTRL+S", Keybindings.normalize("Ctrl+S"));
  }

  @Test
  void cmdAndCtrlAreDifferentShortcutsOnMac() {
    assertNotEquals(
        Keybindings.normalize("Ctrl+S", true),
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, true));
    assertNotEquals(
        Keybindings.normalize("Cmd+S", false),
        Keybindings.normalize(Keybindings.SAVE_DEFAULT, false));
  }

  @Test
  void defaultsDoNotConflictWithEachOther() {
    assertTrue(keys(true).problems().isEmpty());
    assertTrue(keys(false).problems().isEmpty());
  }

  @Test
  void anotherActionOnCtrlSOffMacConflictsWithTheDefaultSave() {
    Keybindings keys = keys(false);
    keys.register("view.other", "View > Other", "Ctrl+S");
    assertEquals(Map.of("Shortcut+S", List.of("file.save", "view.other")), keys.conflicts());
  }

  @Test
  void anotherActionOnCtrlSOnMacDoesNotConflictWithCmdS() {
    Keybindings keys = keys(true);
    keys.register("view.other", "View > Other", "Ctrl+S");
    assertTrue(keys.conflicts().isEmpty());
  }

  @Test
  void anotherActionOnCmdSOnMacConflictsWithTheDefaultSave() {
    Keybindings keys = keys(true);
    keys.register("view.other", "View > Other", "Cmd+S");
    assertEquals(List.of("file.save", "view.other"), keys.conflicts().get("Shortcut+S"));
  }

  @Test
  void aUserOverrideReplacesTheDefault() {
    Keybindings keys = keys(true);
    keys.apply(Map.of("file.save", "Ctrl+S", "file.saveAs", Keybindings.SAVE_AS_DEFAULT));
    assertEquals("Ctrl+S", keys.binding("file.save"), "a Mac user can still choose Ctrl+S");
    assertEquals("Shortcut+Shift+S", keys.binding("file.saveAs"));
  }

  @Test
  void unbindingSaveIsStillPossible() {
    Settings settings = new Settings(dir.resolve("unbind.conf"));
    Keybindings keys = new Keybindings(settings, true);
    keys.register("file.save", "File > Save", Keybindings.SAVE_DEFAULT);
    settings.put(Scope.USER, "key.file.save", "none");
    assertEquals("", keys.binding("file.save"));
  }

  @Test
  void applyingTheEquivalentOfTheDefaultStoresNothing() {
    Keybindings mac = keys(true);
    mac.apply(Map.of("file.save", "Cmd+S", "file.saveAs", "Cmd+Shift+S"));
    assertTrue(mac.problems().isEmpty());
    Settings settings = new Settings(dir.resolve("equivalent.conf"));
    Keybindings win = new Keybindings(settings, false);
    win.register("file.save", "File > Save", Keybindings.SAVE_DEFAULT);
    win.apply(Map.of("file.save", "ctrl+s"));
    assertNull(
        settings.getUser("key.file.save"), "Ctrl+S is the default off macOS, so it is not stored");
    Settings macSettings = new Settings(dir.resolve("equivalent-mac.conf"));
    Keybindings macKeys = new Keybindings(macSettings, true);
    macKeys.register("file.save", "File > Save", Keybindings.SAVE_DEFAULT);
    macKeys.apply(Map.of("file.save", "Cmd+S"));
    assertNull(
        macSettings.getUser("key.file.save"), "Cmd+S is the default on macOS, so it is not stored");
  }

  @Test
  void templateShowsTheDefaultsAsWritten() {
    String template = keys(true).template();
    assertTrue(template.contains("#key.file.save=Shortcut+S\n"));
    assertTrue(template.contains("#key.file.saveAs=Shortcut+Shift+S\n"));
  }

  @Test
  void osDetectionFollowsTheOsNameProperty() {
    String original = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Mac OS X");
      assertTrue(Keybindings.isMacOs());
      System.setProperty("os.name", "Linux");
      assertFalse(Keybindings.isMacOs());
      System.setProperty("os.name", "Windows 11");
      assertFalse(Keybindings.isMacOs());
    } finally {
      System.setProperty("os.name", original);
    }
  }

  @Test
  void theDefaultConstructorUsesTheRunningPlatform() {
    Keybindings keys = new Keybindings(new Settings(dir.resolve("platform.conf")));
    keys.register("file.save", "File > Save", Keybindings.SAVE_DEFAULT);
    keys.register("other", "Other", Keybindings.isMacOs() ? "Cmd+S" : "Ctrl+S");
    assertEquals(List.of("file.save", "other"), keys.conflicts().get("Shortcut+S"));
  }
}
