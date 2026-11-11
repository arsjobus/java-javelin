package com.javelin.ai.profile;

import com.javelin.ai.AiConfig;
import com.javelin.ai.symbols.SymbolBudget;
import com.javelin.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the {@link ModelProfile} for a request. {@code ai.profile} is {@code small}, {@code
 * frontier} or {@code custom}; without it the profile is suggested (a model that runs on this
 * computer is {@code small}, one that does not is {@code frontier}) and the setting always
 * overrides the suggestion. {@code custom} starts from {@code small} and takes its four numbers
 * from {@code ai.profile.*}. The user settings file only, like every {@code ai.*} key.
 */
public final class ModelProfiles {
  public static final String PROFILE = "ai.profile";
  public static final String CUSTOM_RETRIES = "ai.profile.retries";
  public static final String CUSTOM_STEPS = "ai.profile.maxSteps";
  public static final String CUSTOM_STRUCTURED = "ai.profile.structured";
  public static final String CUSTOM_SYMBOLS = "ai.profile.symbols";

  public static final List<String> NAMES = List.of("small", "frontier", "custom");

  private static final Pattern PARAMETERS =
      Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)b(?![a-z0-9])");

  private ModelProfiles() {}

  /** The profile for a model when nothing is set. Uses what the provider declares, not its name. */
  public static ModelProfile suggest(boolean providerIsLocal) {
    return providerIsLocal ? ModelProfile.SMALL : ModelProfile.FRONTIER;
  }

  /** The profile in force: the setting if there is a valid one, else the suggestion. */
  public static ModelProfile resolve(Settings settings, boolean providerIsLocal) {
    String wanted = settings.get(PROFILE, "").strip().toLowerCase(Locale.ROOT);
    return switch (wanted) {
      case "small" -> ModelProfile.SMALL;
      case "frontier" -> ModelProfile.FRONTIER;
      case "custom" -> custom(settings);
      default -> suggest(providerIsLocal);
    };
  }

  /** Small, with the four numbers {@code ai.profile.*} may change. */
  public static ModelProfile custom(Settings settings) {
    ModelProfile base = ModelProfile.SMALL;
    return new ModelProfile(
        "custom",
        base.editsPerProposal(),
        base.editFormat(),
        settings.getBoolean(CUSTOM_STRUCTURED, base.prefersStructuredOutput()),
        settings.getInt(CUSTOM_RETRIES, base.retries()),
        base.planning(),
        settings.getInt(CUSTOM_STEPS, base.maxSteps()),
        SymbolBudget.named(settings.get(CUSTOM_SYMBOLS, "")).orElse(base.symbolBudget()));
  }

  /**
   * The configuration with edits asked for as JSON when the profile prefers it, unless {@code
   * ai.structuredEdits} is set: the explicit setting wins. Whether the provider can be held to a
   * schema is checked where the request is built ({@code AiProvider#supportsSchemaOutput}).
   */
  public static AiConfig apply(AiConfig config, Settings settings, ModelProfile profile) {
    if (settings.get(AiConfig.STRUCTURED_EDITS, null) != null) return config;
    return config.withStructuredEdits(profile.prefersStructuredOutput());
  }

  /** One line for people: which profile, and why. */
  public static String describe(Settings settings, String model, boolean providerIsLocal) {
    ModelProfile profile = resolve(settings, providerIsLocal);
    String set = settings.get(PROFILE, "").strip();
    if (!set.isEmpty() && NAMES.contains(set.toLowerCase(Locale.ROOT)))
      return profile.id() + " (set by " + PROFILE + ")";
    OptionalDouble size = parameterBillions(model);
    String what =
        (model == null || model.isBlank() ? "the model" : model)
            + (size.isPresent() ? " (about " + trim(size.getAsDouble()) + "B parameters)" : "");
    return profile.id()
        + " (suggested: "
        + what
        + (providerIsLocal ? " runs on this computer" : " is served by an outside provider")
        + ")";
  }

  /** The parameter count a tag such as {@code qwen3.5:9b} names, in billions, when it names one. */
  public static OptionalDouble parameterBillions(String model) {
    if (model == null) return OptionalDouble.empty();
    Matcher m = PARAMETERS.matcher(model.toLowerCase(Locale.ROOT));
    return m.find() ? OptionalDouble.of(Double.parseDouble(m.group(1))) : OptionalDouble.empty();
  }

  /** Problems with the current values, for display; {@link #resolve} falls back silently. */
  public static List<String> validate(Settings settings) {
    List<String> problems = new ArrayList<>();
    String profile = settings.get(PROFILE, null);
    if (profile != null
        && !profile.isBlank()
        && !NAMES.contains(profile.strip().toLowerCase(Locale.ROOT)))
      problems.add(
          PROFILE
              + ": '"
              + profile
              + "' is not one of "
              + String.join(", ", NAMES)
              + "; the profile is suggested from the provider instead");
    intInRange(settings, CUSTOM_RETRIES, 0, 5, problems);
    intInRange(settings, CUSTOM_STEPS, 1, 8, problems);
    String structured = settings.get(CUSTOM_STRUCTURED, null);
    if (structured != null
        && !structured.strip().equalsIgnoreCase("true")
        && !structured.strip().equalsIgnoreCase("false"))
      problems.add(
          CUSTOM_STRUCTURED + ": '" + structured + "' is not true or false; false is used");
    String symbols = settings.get(CUSTOM_SYMBOLS, null);
    if (symbols != null && SymbolBudget.named(symbols).isEmpty())
      problems.add(CUSTOM_SYMBOLS + ": '" + symbols + "' is not small or frontier; small is used");
    return problems;
  }

  private static void intInRange(
      Settings settings, String key, int min, int max, List<String> problems) {
    String raw = settings.get(key, null);
    if (raw == null) return;
    try {
      int value = Integer.parseInt(raw.trim());
      if (value < min || value > max)
        problems.add(
            key + ": " + value + " is outside " + min + "-" + max + "; the nearest value is used");
    } catch (NumberFormatException ex) {
      problems.add(key + ": '" + raw + "' is not a whole number; the default is used");
    }
  }

  private static String trim(double value) {
    return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
  }
}
