package com.javelin.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.javelin.settings.LayoutPrefs;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window layout across runs and across windows, through the same services the window uses (the
 * dividers themselves: {@code WorkAreaTest}).
 */
class LayoutWorkflowE2ETest {
  @TempDir Path home;

  private Path file() {
    return home.resolve("settings.conf");
  }

  @Test
  void theDividersComeBackWhereTheUserLeftThemAndOtherSettingsSurvive() throws IOException {
    Files.writeString(file(), "# mine\ntheme=dark\n");
    LayoutPrefs run1 = new LayoutPrefs(new Settings(file()));
    assertFalse(run1.sidebarWidth().isPresent(), "a first run uses the defaults");
    run1.rememberSidebarWidth(333);
    run1.rememberBottomHeight(210);

    Settings run2 = new Settings(file());
    assertEquals(333, new LayoutPrefs(run2).sidebarWidth().getAsDouble());
    assertEquals(210, new LayoutPrefs(run2).bottomHeight().getAsDouble());
    assertEquals("dark", run2.get("theme", ""));
    assertEquals("# mine", Files.readAllLines(file()).get(0));
  }

  @Test
  void twoProjectWindowsAndTheSettingsDialogDoNotOverwriteEachOther() throws IOException {
    Settings windowA = new Settings(file());
    Settings windowB = new Settings(file());
    windowB.put(Scope.USER, "theme", "light"); // the Settings dialog in B
    windowB.save(Scope.USER);
    new LayoutPrefs(windowA)
        .rememberSidebarWidth(280); // A was started before the change and still holds the old theme

    Settings next = new Settings(file());
    assertEquals("light", next.get("theme", ""));
    assertEquals(280, new LayoutPrefs(next).sidebarWidth().getAsDouble());
  }

  @Test
  void everyEditorWideSaveLeavesAnotherWindowsLayoutAndModelAlone() throws IOException {
    Files.writeString(file(), "theme=dark\n");
    Settings windowA = new Settings(file()); // the window the user picks a theme in
    Settings windowB = new Settings(file());
    new LayoutPrefs(windowB).rememberSidebarWidth(360);
    windowB.put(Scope.USER, "ai.model", "llama3");
    windowB.saveKeys(Scope.USER, List.of("ai.model"));

    windowA.put(
        Scope.USER,
        "theme",
        "light"); // Theme menu, search "match case" and the Settings dialog all end in save
    windowA.save(Scope.USER);

    Settings next = new Settings(file());
    assertEquals("light", next.get("theme", ""));
    assertEquals(360, new LayoutPrefs(next).sidebarWidth().getAsDouble());
    assertEquals("llama3", next.get("ai.model", ""));
  }
}
