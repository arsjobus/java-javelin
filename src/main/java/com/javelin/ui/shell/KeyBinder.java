package com.javelin.ui.shell;

import com.javelin.Branding;
import com.javelin.settings.Keybindings;
import com.javelin.ui.Shortcuts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.MenuItem;

/**
 * Creates menu items whose shortcuts can be rebound: each one's id and default are registered with
 * {@link Keybindings}.
 */
public final class KeyBinder {
  private final Keybindings keys;
  private final Map<String, MenuItem> menuItems = new LinkedHashMap<>();
  private final List<String> problems = new ArrayList<>();

  public KeyBinder(Keybindings keys) {
    this.keys = keys;
  }

  public MenuItem bound(
      String menu, String text, String id, String defaultBinding, Runnable action) {
    MenuItem item = new MenuItem(text);
    item.setOnAction(e -> action.run());
    keys.register(id, menu + " > " + text.replace("...", ""), defaultBinding);
    menuItems.put(id, item);
    item.setAccelerator(Shortcuts.parse(keys.binding(id)));
    return item;
  }

  /**
   * Applies the current bindings to every registered item and remembers shortcuts that could not be
   * understood.
   */
  public void apply() {
    problems.clear();
    for (Keybindings.Action action : keys.actions()) {
      MenuItem item = menuItems.get(action.id());
      if (item == null) continue;
      String binding = keys.binding(action.id());
      var combination = Shortcuts.parse(binding);
      if (!binding.isEmpty() && combination == null)
        problems.add(
            "key."
                + action.id()
                + ": '"
                + binding
                + "' is not a shortcut "
                + Branding.FULL_NAME
                + " understands");
      item.setAccelerator(combination);
    }
  }

  /** Shortcuts the last {@link #apply()} could not use. */
  public List<String> problems() {
    return problems;
  }
}
