package com.javelin.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayoutPrefsTest {
  @TempDir Path dir;

  private Path file() {
    return dir.resolve("settings.conf");
  }

  private LayoutPrefs prefs() {
    return new LayoutPrefs(new Settings(file()));
  }

  private void write(String text) throws IOException {
    Files.writeString(file(), text, StandardCharsets.UTF_8);
  }

  @Test
  void nothingIsRememberedUntilTheUserDragsADivider() {
    LayoutPrefs prefs = prefs();
    assertFalse(prefs.sidebarWidth().isPresent());
    assertFalse(prefs.bottomHeight().isPresent());
  }

  @Test
  void theSizesComeBackInTheNextRun() throws IOException {
    LayoutPrefs first = prefs();
    first.rememberSidebarWidth(312);
    first.rememberBottomHeight(240);

    LayoutPrefs nextRun = prefs();
    assertEquals(312, nextRun.sidebarWidth().getAsDouble());
    assertEquals(240, nextRun.bottomHeight().getAsDouble());
  }

  @Test
  void sizesAreRoundedToWholePixels() throws IOException {
    LayoutPrefs prefs = prefs();
    prefs.rememberSidebarWidth(312.6);
    assertEquals(313, prefs().sidebarWidth().getAsDouble());
    assertTrue(Files.readString(file()).contains("layout.sidebarWidth=313"));
  }

  @Test
  void theLatestSizeWins() throws IOException {
    LayoutPrefs prefs = prefs();
    prefs.rememberSidebarWidth(300);
    prefs.rememberSidebarWidth(420);
    assertEquals(420, prefs().sidebarWidth().getAsDouble());
    assertEquals(
        1,
        Files.readAllLines(file()).stream()
            .filter(l -> l.startsWith("layout.sidebarWidth="))
            .count(),
        "one line, not one per drag");
  }

  @Test
  void aSizeThatIsAlreadySavedWritesNothing() throws IOException {
    LayoutPrefs prefs = prefs();
    prefs.rememberSidebarWidth(300);
    var modified = Files.getLastModifiedTime(file());
    Files.setLastModifiedTime(
        file(), java.nio.file.attribute.FileTime.fromMillis(modified.toMillis() - 60_000));
    var before = Files.getLastModifiedTime(file());
    prefs.rememberSidebarWidth(300.2); // rounds to the same 300
    assertEquals(before, Files.getLastModifiedTime(file()));
  }

  @Test
  void aWindowThatHasNoSizeYetDoesNotSaveZero() throws IOException {
    LayoutPrefs prefs = prefs();
    prefs.rememberSidebarWidth(0);
    prefs.rememberSidebarWidth(-5);
    prefs.rememberSidebarWidth(Double.NaN);
    prefs.rememberBottomHeight(Double.POSITIVE_INFINITY);
    prefs.rememberBottomHeight(LayoutPrefs.LARGEST + 1);
    assertFalse(
        Files.exists(file()), "nothing was worth writing, so the file was not even created");
  }

  @Test
  void absurdSavedValuesAreIgnored() throws IOException {
    write("layout.sidebarWidth=wide\nlayout.bottomHeight=-4\n");
    LayoutPrefs prefs = prefs();
    assertFalse(prefs.sidebarWidth().isPresent());
    assertFalse(prefs.bottomHeight().isPresent());
    write("layout.sidebarWidth=0\nlayout.bottomHeight=99999999\n");
    prefs = prefs();
    assertFalse(prefs.sidebarWidth().isPresent());
    assertFalse(prefs.bottomHeight().isPresent());
    write("layout.sidebarWidth=NaN\nlayout.bottomHeight=\n");
    prefs = prefs();
    assertFalse(prefs.sidebarWidth().isPresent());
    assertFalse(prefs.bottomHeight().isPresent());
  }

  @Test
  void theUsersOtherSettingsAndCommentsAreLeftAlone() throws IOException {
    write("# my settings\ntheme=dark\n\neditor.tabSize=2\n");
    prefs().rememberSidebarWidth(280);
    assertEquals(
        "# my settings\ntheme=dark\n\neditor.tabSize=2\nlayout.sidebarWidth=280\n",
        Files.readString(file()));
  }

  @Test
  void aProjectCannotMoveTheDividers() throws IOException {
    Settings settings = new Settings(file());
    settings.setProject(dir.resolve("project"));
    Files.createDirectories(settings.projectFile().getParent());
    Files.writeString(settings.projectFile(), "layout.sidebarWidth=900\n");
    settings.reload();
    assertFalse(
        new LayoutPrefs(settings).sidebarWidth().isPresent(),
        "layout.* is read from the user file only");
    assertTrue(Settings.isUserOnly(LayoutPrefs.SIDEBAR_WIDTH));
    assertTrue(Settings.isUserOnly(LayoutPrefs.BOTTOM_HEIGHT));
  }

  @Test
  void forgettingGoesBackToTheDefaultsAndKeepsEverythingElse() throws IOException {
    write("theme=dark\n");
    LayoutPrefs prefs = prefs();
    prefs.rememberSidebarWidth(300);
    prefs.rememberBottomHeight(200);
    prefs.forget();
    assertEquals("theme=dark\n", Files.readString(file()));
    assertFalse(prefs().sidebarWidth().isPresent());
  }

  @Test
  void twoWindowsDoNotUndoEachOthersSizes() throws IOException {
    LayoutPrefs windowA = prefs(); // both started before either saved anything
    LayoutPrefs windowB = prefs();
    windowA.rememberSidebarWidth(310);
    windowB.rememberBottomHeight(230);
    LayoutPrefs nextRun = prefs();
    assertEquals(
        310,
        nextRun.sidebarWidth().getAsDouble(),
        "window B's save must not have erased A's width");
    assertEquals(230, nextRun.bottomHeight().getAsDouble());
  }

  @Test
  void aSavedLayoutDoesNotRevertASettingChangedByAnotherWindow() throws IOException {
    write("theme=dark\n");
    Settings windowA = new Settings(file());
    // Another window (or the Settings dialog) changes the theme after window A started.
    Settings other = new Settings(file());
    other.put(Scope.USER, "theme", "light");
    other.save(Scope.USER);

    new LayoutPrefs(windowA).rememberSidebarWidth(333);
    Settings nextRun = new Settings(file());
    assertEquals(
        "light",
        nextRun.get("theme", ""),
        "saving a layout writes the layout, not window A's old copy of the theme");
    assertEquals(333, new LayoutPrefs(nextRun).sidebarWidth().getAsDouble());
  }
}
