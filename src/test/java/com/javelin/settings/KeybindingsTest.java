package com.javelin.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeybindingsTest {
  @TempDir Path dir;
  private Settings settings;
  private Keybindings keys;

  @BeforeEach
  void setUp() {
    settings = new Settings(dir.resolve("settings.conf"));
    keys = new Keybindings(settings);
    keys.register("file.save", "File > Save", "Ctrl+S");
    keys.register("file.open", "File > Open", " Ctrl+O ");
    keys.register("view.zoom", "View > Zoom", null);
  }

  @Test
  void registryExposesActionsInRegistrationOrder() {
    assertEquals(
        List.of("file.save", "file.open", "view.zoom"),
        keys.actions().stream().map(Keybindings.Action::id).toList());
    assertEquals("Ctrl+O", keys.action("file.open").defaultBinding());
    assertEquals("", keys.action("view.zoom").defaultBinding());
    assertNull(keys.action("missing"));
    assertEquals("", keys.binding("missing"));
  }

  @Test
  void overridesComeFromTheUserSettings() {
    assertEquals("Ctrl+S", keys.binding("file.save"));
    settings.put(Scope.USER, "key.file.save", "Ctrl+Shift+S");
    assertEquals("Ctrl+Shift+S", keys.binding("file.save"));
    settings.put(Scope.USER, "key.file.save", "none");
    assertEquals("", keys.binding("file.save"));
    settings.put(Scope.USER, "key.file.save", "NONE");
    assertEquals("", keys.binding("file.save"));
    settings.put(Scope.USER, "key.file.save", "");
    assertEquals("", keys.binding("file.save"));
    assertEquals(Map.of("file.save", "", "file.open", "Ctrl+O", "view.zoom", ""), keys.effective());
  }

  @Test
  void applyStoresOnlyDifferencesFromTheDefaults() {
    settings.put(Scope.USER, "key.file.save", "Ctrl+Q");
    keys.apply(Map.of("file.save", "ctrl+s", "file.open", "", "view.zoom", "Ctrl+Plus"));
    assertNull(settings.getUser("key.file.save"), "a binding equal to the default is not stored");
    assertEquals("none", settings.getUser("key.file.open"));
    assertEquals("Ctrl+Plus", settings.getUser("key.view.zoom"));
    keys.apply(Map.of());
    assertTrue(settings.userKeys("key.").isEmpty(), "missing entries mean 'use the default'");
    keys.apply(Map.of("view.zoom", "none"));
    assertNull(settings.getUser("key.view.zoom"), "'none' equals an empty default");
    java.util.HashMap<String, String> unbind = new java.util.HashMap<>();
    unbind.put("file.save", null);
    keys.apply(unbind);
    assertEquals("none", settings.getUser("key.file.save"), "a null wish means 'no shortcut'");
  }

  @Test
  void conflictsAreFoundAcrossSpellings() {
    settings.put(Scope.USER, "key.file.open", "control+s");
    Map<String, List<String>> conflicts = keys.conflicts();
    assertEquals(Map.of("Ctrl+S", List.of("file.save", "file.open")), conflicts);
    keys.register("view.zoom", "Zoom", "Ctrl+S");
    assertEquals(List.of("file.save", "file.open", "view.zoom"), keys.conflicts().get("Ctrl+S"));
  }

  @Test
  void problemsListUnknownIdsAndConflicts() throws IOException {
    assertTrue(keys.problems().isEmpty());
    settings.put(Scope.USER, "key.ghost", "Ctrl+G");
    settings.put(Scope.USER, "key.file.open", "Ctrl+S");
    List<String> problems = keys.problems();
    assertEquals(2, problems.size());
    assertTrue(problems.get(0).contains("key.ghost") && problems.get(0).contains("no action"));
    assertTrue(
        problems.get(1).contains("Ctrl+S") && problems.get(1).contains("file.save, file.open"));
  }

  @Test
  void templateHasOneCommentedLinePerAction() {
    assertEquals(
        "# File > Save\n#key.file.save=Ctrl+S\n# File > Open\n#key.file.open=Ctrl+O\n# View > Zoom\n#key.view.zoom=none\n",
        keys.template());
  }

  @Test
  void normalizeIsCaseOrderAndSynonymInsensitive() {
    assertEquals("CTRL+SHIFT+F", Keybindings.normalize("shift + Ctrl + f"));
    assertEquals(
        Keybindings.normalize("Ctrl+Shift+Alt+Meta+K"),
        Keybindings.normalize("control+shift+option+cmd+k"));
    assertEquals("CTRL+DIGIT1", Keybindings.normalize("Ctrl+1"));
    assertEquals(
        "SHORTCUT+COMMAND_X".replace("COMMAND_X", "X"), Keybindings.normalize("Shortcut+x"));
    assertEquals("CTRL+PAGE_UP", Keybindings.normalize("Ctrl+Page Up"));
    assertEquals("F5", Keybindings.normalize("f5"));
    for (String empty : new String[] {null, "", "   ", "none", " None "})
      assertEquals("", Keybindings.normalize(empty));
  }

  @Test
  void plusCanBeTheKey() {
    assertEquals("CTRL+PLUS", Keybindings.normalize("Ctrl++"));
    assertNotEquals(Keybindings.normalize("Ctrl++"), Keybindings.normalize("Ctrl+-"));
    assertNotEquals(Keybindings.normalize("Ctrl++"), Keybindings.normalize("Ctrl"));
    assertEquals(Keybindings.normalize("Ctrl++"), Keybindings.normalize("Ctrl+Plus"));
  }

  @Test
  void aBindingWithOnlyModifiersHasNoDanglingPlus() {
    assertEquals("CTRL", Keybindings.normalize("Ctrl"));
    assertEquals("CTRL+SHIFT", Keybindings.normalize("Ctrl+Shift"));
  }
}
