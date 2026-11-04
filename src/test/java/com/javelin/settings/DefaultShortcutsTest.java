package com.javelin.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every default shortcut that needs a command key must use the platform key ({@code Shortcut+}: Cmd
 * on macOS, Ctrl elsewhere), so a Mac user gets Cmd+F, Cmd+B and so on rather than Ctrl+F and
 * Ctrl+B.
 */
class DefaultShortcutsTest {
  @TempDir Path dir;

  private static boolean isFunctionKeyOnly(String binding) {
    String key = binding.substring(binding.lastIndexOf('+') + 1);
    return key.matches("F\\d{1,2}") && !binding.toUpperCase().contains("SHORTCUT");
  }

  private static List<String> commandBindings() {
    return DefaultShortcuts.ALL.values().stream().filter(b -> !isFunctionKeyOnly(b)).toList();
  }

  private Keybindings registered(boolean mac) {
    Keybindings keys = new Keybindings(new Settings(dir.resolve("settings-" + mac + ".conf")), mac);
    DefaultShortcuts.ALL.forEach((id, binding) -> keys.register(id, id, binding));
    return keys;
  }

  @Test
  void noDefaultIsTiedToCtrl() {
    DefaultShortcuts.ALL.forEach(
        (id, binding) -> {
          String normal = Keybindings.normalize(binding);
          assertFalse(
              normal.contains("CTRL"), id + " still defaults to a Ctrl shortcut: " + binding);
          assertFalse(binding.toLowerCase().contains("control"), id + ": " + binding);
        });
  }

  @Test
  void everyCommandKeyDefaultUsesThePlatformShortcutKey() {
    assertFalse(commandBindings().isEmpty());
    for (String binding : commandBindings()) {
      assertTrue(binding.startsWith("Shortcut+"), binding + " should start with Shortcut+");
    }
  }

  @Test
  void onMacEveryCommandKeyDefaultIsCmd() {
    for (String binding : commandBindings()) {
      String normal = Keybindings.normalize(binding, true);
      assertTrue(normal.contains("META"), binding + " -> " + normal);
      assertFalse(normal.contains("CTRL"), binding + " -> " + normal);
    }
  }

  @Test
  void offMacEveryCommandKeyDefaultIsStillCtrl() {
    for (String binding : commandBindings()) {
      String normal = Keybindings.normalize(binding, false);
      assertTrue(normal.contains("CTRL"), binding + " -> " + normal);
      assertFalse(normal.contains("META"), binding + " -> " + normal);
    }
  }

  @Test
  void specificMacDefaultsAreTheFamiliarCmdShortcuts() {
    assertEquals("META+F", Keybindings.normalize(DefaultShortcuts.SEARCH, true));
    assertEquals("META+B", Keybindings.normalize(DefaultShortcuts.BUILD, true));
    assertEquals("META+COMMA", Keybindings.normalize(DefaultShortcuts.SETTINGS, true));
    assertEquals("SHIFT+META+F", Keybindings.normalize(DefaultShortcuts.FORMAT, true));
    assertEquals("META+DIGIT0", Keybindings.normalize(DefaultShortcuts.ZOOM_RESET, true));
    assertEquals("CTRL+F", Keybindings.normalize(DefaultShortcuts.SEARCH, false));
    assertEquals("CTRL+B", Keybindings.normalize(DefaultShortcuts.BUILD, false));
  }

  @Test
  void functionKeyDefaultsAreUnchanged() {
    assertEquals("F3", DefaultShortcuts.FIND_NEXT);
    assertEquals("Shift+F3", DefaultShortcuts.FIND_PREVIOUS);
    assertEquals("F12", DefaultShortcuts.GO_TO_DEFINITION);
    assertEquals("Shift+F12", DefaultShortcuts.FIND_REFERENCES);
    assertEquals("F2", DefaultShortcuts.RENAME);
    assertEquals("F5", DefaultShortcuts.RUN_COMMAND);
  }

  @Test
  void saveShortcutsAreTheSameConstantsAsBefore() {
    assertEquals(Keybindings.SAVE_DEFAULT, DefaultShortcuts.SAVE);
    assertEquals(Keybindings.SAVE_AS_DEFAULT, DefaultShortcuts.SAVE_AS);
  }

  @Test
  void defaultsDoNotConflictOnEitherPlatform() {
    for (boolean mac : new boolean[] {true, false}) {
      Keybindings keys = registered(mac);
      assertTrue(keys.conflicts().isEmpty(), "conflicts on mac=" + mac + ": " + keys.conflicts());
      assertTrue(keys.problems().isEmpty(), "problems on mac=" + mac + ": " + keys.problems());
    }
  }

  @Test
  void applyingTheEquivalentCmdDefaultsStoresNothing() {
    Settings settings = new Settings(dir.resolve("equivalent.conf"));
    Keybindings keys = new Keybindings(settings, true);
    DefaultShortcuts.ALL.forEach((id, binding) -> keys.register(id, id, binding));
    keys.apply(
        Map.of("edit.search", "Cmd+F", "run.build", "Command+B", "settings.open", "meta+comma"));
    assertTrue(
        settings.userKeys(Keybindings.PREFIX).isEmpty(),
        "Cmd+F etc. are the defaults on macOS, so nothing is stored");
  }

  @Test
  void aMacUserCanStillChooseCtrl() {
    Keybindings keys = registered(true);
    keys.apply(Map.of("edit.search", "Ctrl+F"));
    assertEquals("Ctrl+F", keys.binding("edit.search"));
    assertEquals(
        Keybindings.normalize("Ctrl+F", true),
        Keybindings.normalize(keys.binding("edit.search"), true));
    assertNotEquals(
        Keybindings.normalize(DefaultShortcuts.SEARCH, true),
        Keybindings.normalize("Ctrl+F", true));
  }

  @Test
  void theMapListsEveryConstant() throws IllegalAccessException {
    Set<String> constants = new HashSet<>();
    for (Field field : DefaultShortcuts.class.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers()) && field.getType() == String.class)
        constants.add((String) field.get(null));
    }
    assertTrue(
        DefaultShortcuts.ALL.values().containsAll(constants),
        "a constant is missing from ALL: " + constants);
    assertEquals(constants.size(), new HashSet<>(DefaultShortcuts.ALL.values()).size());
  }

  // ---- guards against the defaults drifting back to hard-coded Ctrl strings ----

  @Test
  void appRegistersEveryDefaultFromTheConstants() throws IOException {
    String app = Files.readString(Path.of("src/main/java/com/javelin/ui/menu/MainMenu.java"));
    List<String> literals = new ArrayList<>();
    var matcher =
        Pattern.compile("\"((?:Ctrl|Control|Meta|Cmd|Command|Alt|Shortcut)\\+[^\"]*)\"")
            .matcher(app);
    while (matcher.find()) literals.add(matcher.group(1));
    assertTrue(
        literals.isEmpty(),
        "MainMenu hard-codes shortcut strings instead of DefaultShortcuts: " + literals);
    DefaultShortcuts.ALL
        .keySet()
        .forEach(
            id ->
                assertTrue(
                    Pattern.compile("\"" + Pattern.quote(id) + "\",\\s*DefaultShortcuts\\.[A-Z_]+")
                        .matcher(app)
                        .find(),
                    "MainMenu does not register " + id + " from DefaultShortcuts"));
  }

  @Test
  void customizationDocTableMatchesTheDefaults() throws IOException {
    List<String> lines = Files.readAllLines(Path.of("CUSTOMIZATION.md"));
    for (var entry : DefaultShortcuts.ALL.entrySet()) {
      String row =
          lines.stream()
              .filter(l -> l.startsWith("| `" + entry.getKey() + "` |"))
              .findFirst()
              .orElse(null);
      assertNotNull(row, "CUSTOMIZATION.md has no row for " + entry.getKey());
      assertTrue(
          row.contains("`" + entry.getValue() + "`"), row + " should show " + entry.getValue());
    }
    lines.stream()
        .filter(l -> l.startsWith("| `") && l.contains("`Ctrl+"))
        .findAny()
        .ifPresent(l -> fail("a default in the docs still says Ctrl: " + l));
  }
}
