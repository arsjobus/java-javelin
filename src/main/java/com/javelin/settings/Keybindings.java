package com.javelin.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The registry of rebindable actions and their shortcuts. Bindings are plain strings such as {@code
 * Ctrl+Shift+F}; overrides live in the user settings as {@code key.<action id>=...} ({@code none}
 * unbinds an action). This class is JavaFX-free: turning a string into a key combination is the
 * UI's job.
 */
public final class Keybindings {
  public static final String NONE = "none";
  public static final String PREFIX = "key.";

  /** Default for File > Save: {@code Shortcut} is Cmd on macOS and Ctrl on Windows/Linux. */
  public static final String SAVE_DEFAULT = "Shortcut+S";

  /** Default for File > Save As. */
  public static final String SAVE_AS_DEFAULT = "Shortcut+Shift+S";

  /**
   * An action the user can bind. {@code defaultBinding} is empty when the action has no shortcut by
   * default.
   */
  public record Action(String id, String label, String defaultBinding) {}

  private final Settings settings;
  private final boolean mac;
  private final Map<String, Action> actions = new LinkedHashMap<>();

  public Keybindings(Settings settings) {
    this(settings, isMacOs());
  }

  /**
   * {@code mac} decides what {@code Shortcut} means when bindings are compared: Meta (Cmd) if true,
   * else Ctrl.
   */
  public Keybindings(Settings settings, boolean mac) {
    this.settings = settings;
    this.mac = mac;
  }

  /** True when running on macOS, where the platform shortcut key is Cmd rather than Ctrl. */
  public static boolean isMacOs() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
  }

  public void register(String id, String label, String defaultBinding) {
    actions.put(id, new Action(id, label, defaultBinding == null ? "" : defaultBinding.strip()));
  }

  public List<Action> actions() {
    return List.copyOf(actions.values());
  }

  public Action action(String id) {
    return actions.get(id);
  }

  /** The shortcut in effect for an action, or an empty string when it is unbound. */
  public String binding(String id) {
    Action action = actions.get(id);
    if (action == null) return "";
    String raw = settings.get(PREFIX + id, null);
    if (raw == null) return action.defaultBinding();
    return raw.isBlank() || raw.equalsIgnoreCase(NONE) ? "" : raw;
  }

  /** Every registered action with its effective shortcut ("" when unbound). */
  public Map<String, String> effective() {
    Map<String, String> result = new LinkedHashMap<>();
    for (Action action : actions.values()) result.put(action.id(), binding(action.id()));
    return result;
  }

  /**
   * Records the wanted shortcuts as user-level overrides, keeping only the ones that differ from
   * the defaults. Changes are in memory until {@link Settings#save} is called.
   */
  public void apply(Map<String, String> wanted) {
    for (Action action : actions.values()) {
      String key = PREFIX + action.id();
      String want = wanted.getOrDefault(action.id(), action.defaultBinding());
      if (normalize(want, mac).equals(normalize(action.defaultBinding(), mac)))
        settings.remove(Settings.Scope.USER, key);
      else settings.put(Settings.Scope.USER, key, want == null || want.isBlank() ? NONE : want);
    }
  }

  /** Shortcuts used by more than one action, keyed by the shortcut as it is currently written. */
  public Map<String, List<String>> conflicts() {
    Map<String, List<String>> byBinding = new LinkedHashMap<>();
    Map<String, String> spelling = new LinkedHashMap<>();
    for (Action action : actions.values()) {
      String binding = binding(action.id());
      String normal = normalize(binding, mac);
      if (normal.isEmpty()) continue;
      byBinding.computeIfAbsent(normal, k -> new ArrayList<>()).add(action.id());
      spelling.putIfAbsent(normal, binding);
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    byBinding.forEach(
        (normal, ids) -> {
          if (ids.size() > 1) result.put(spelling.get(normal), ids);
        });
    return result;
  }

  /** Human-readable problems with the current bindings: unknown action ids and shared shortcuts. */
  public List<String> problems() {
    List<String> problems = new ArrayList<>();
    for (String key : settings.userKeys(PREFIX)) {
      String id = key.substring(PREFIX.length());
      if (!actions.containsKey(id)) problems.add(key + ": there is no action with this id");
    }
    conflicts()
        .forEach(
            (binding, ids) ->
                problems.add(
                    "shortcut "
                        + binding
                        + " is bound to several actions: "
                        + String.join(", ", ids)));
    return problems;
  }

  /** One commented line per action, for the settings file template. */
  public String template() {
    StringBuilder text = new StringBuilder();
    for (Action action : actions.values()) {
      text.append("# ").append(action.label()).append('\n');
      text.append('#')
          .append(PREFIX)
          .append(action.id())
          .append('=')
          .append(action.defaultBinding().isEmpty() ? NONE : action.defaultBinding())
          .append('\n');
    }
    return text.toString();
  }

  /**
   * A canonical form for comparing shortcuts: case-insensitive, modifiers in a fixed order. Empty
   * for "no shortcut". A literal plus key is written {@code Ctrl++} (or {@code Ctrl+Plus}); both
   * normalize to {@code CTRL+PLUS}.
   */
  public static String normalize(String binding) {
    return normalize(binding, null);
  }

  /**
   * Like {@link #normalize(String)}, but {@code Shortcut} is first resolved to the platform key:
   * Meta when {@code mac} is true, Ctrl when false. So {@code Shortcut+S} and {@code Ctrl+S}
   * compare equal off macOS, and {@code Shortcut+S} equals {@code Cmd+S} on macOS. With {@code mac
   * == null} {@code Shortcut} is kept as written.
   */
  public static String normalize(String binding, Boolean mac) {
    if (binding == null || binding.isBlank() || binding.strip().equalsIgnoreCase(NONE)) return "";
    String text = binding.strip();
    boolean plusKey = text.equals("+") || text.endsWith("++");
    if (plusKey) text = text.substring(0, text.length() - 1);
    boolean ctrl = false, shift = false, alt = false, meta = false, shortcut = false;
    String key = plusKey ? "PLUS" : "";
    for (String raw : text.split("\\+")) {
      String token = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
      if (token.isEmpty()) continue;
      switch (token) {
        case "CTRL", "CONTROL" -> ctrl = true;
        case "SHIFT" -> shift = true;
        case "ALT", "OPTION" -> alt = true;
        case "META", "CMD", "COMMAND" -> meta = true;
        case "SHORTCUT" -> shortcut = true;
        default ->
            key =
                token.length() == 1 && Character.isDigit(token.charAt(0)) ? "DIGIT" + token : token;
      }
    }
    if (shortcut && mac != null) {
      if (mac) meta = true;
      else ctrl = true;
      shortcut = false;
    }
    List<String> parts = new ArrayList<>();
    if (ctrl) parts.add("CTRL");
    if (shift) parts.add("SHIFT");
    if (alt) parts.add("ALT");
    if (meta) parts.add("META");
    if (shortcut) parts.add("SHORTCUT");
    if (!key.isEmpty()) parts.add(key);
    return String.join("+", parts);
  }
}
