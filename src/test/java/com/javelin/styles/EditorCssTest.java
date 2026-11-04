package com.javelin.styles;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the shipped {@code editor.css} without starting JavaFX: the scroll bar handle must be
 * visible against its track in every built-in theme, and the line-number gutter must leave a gap
 * before the text. JavaFX-free, so it runs headless like the rest of the core tests.
 */
class EditorCssTest {
  /** Minimum contrast between handle and track: WCAG 2.x for non-text UI components. */
  private static final double MIN_CONTRAST = 3.0;

  private static List<Rule> rules;

  private record Rule(String selector, Map<String, String> declarations) {}

  @BeforeAll
  static void load() throws IOException {
    try (InputStream in = EditorCssTest.class.getResourceAsStream("/styles/editor.css")) {
      assertNotNull(in, "editor.css must be on the classpath");
      rules = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  // ---- scroll bar handle contrast ----

  @Test
  void lightHandleIsVisibleAgainstTrack() {
    assertHandleContrast(List.of(""), "light");
  }

  @Test
  void darkHandleIsVisibleAgainstTrack() {
    assertHandleContrast(List.of("", ".theme-dark "), "dark");
  }

  @Test
  void highContrastHandleIsVisibleAgainstTrack() {
    assertHandleContrast(List.of("", ".theme-dark ", ".theme-hc "), "high contrast");
  }

  @Test
  void handleStaysVisibleWhenHoveredAndPressed() {
    for (List<String> chain :
        List.of(
            List.of(""), List.of("", ".theme-dark "), List.of("", ".theme-dark ", ".theme-hc "))) {
      String track = effective(chain, ".scroll-bar > .track-background", "-fx-background-color");
      for (String state : List.of(":hover", ":pressed")) {
        String thumb = effective(chain, ".scroll-bar > .thumb" + state, "-fx-background-color");
        assertTrue(
            contrast(thumb, track) >= MIN_CONTRAST, chain + state + " " + thumb + " on " + track);
      }
    }
  }

  @Test
  void handleIsNeverTheSameColourAsItsTrack() {
    for (List<String> chain :
        List.of(
            List.of(""), List.of("", ".theme-dark "), List.of("", ".theme-dark ", ".theme-hc "))) {
      assertNotEquals(
          effective(chain, ".scroll-bar > .track-background", "-fx-background-color"),
          effective(chain, ".scroll-bar > .thumb", "-fx-background-color"));
    }
  }

  @Test
  void trackAndTrackBackgroundAgreeInEveryTheme() {
    for (List<String> chain :
        List.of(
            List.of(""), List.of("", ".theme-dark "), List.of("", ".theme-dark ", ".theme-hc "))) {
      assertEquals(
          effective(chain, ".scroll-bar > .track", "-fx-background-color"),
          effective(chain, ".scroll-bar > .track-background", "-fx-background-color"));
    }
  }

  @Test
  void handleOverridesTheLayeredDefaultWithASingleFill() {
    // The JavaFX default stacks several fills and insets; one fill and one inset value replaces all
    // of them.
    String fill = effective(List.of(""), ".scroll-bar > .thumb", "-fx-background-color");
    String insets = effective(List.of(""), ".scroll-bar > .thumb", "-fx-background-insets");
    assertFalse(fill.contains(","), fill);
    assertFalse(insets.contains(","), insets);
  }

  // ---- gutter spacing ----

  @Test
  void gutterBackgroundStopsShortOfTheText() {
    double gap =
        lastNumber(effective(List.of(""), ".code-area .lineno", "-fx-background-insets"), 1);
    assertTrue(gap >= 6, "gap between gutter and text should be at least 6px, was " + gap);
  }

  @Test
  void lineNumbersKeepRoomInsideTheGutter() {
    String[] padding =
        effective(List.of(""), ".code-area .lineno", "-fx-padding").trim().split("\\s+");
    assertEquals(4, padding.length, "top right bottom left");
    double gap =
        lastNumber(effective(List.of(""), ".code-area .lineno", "-fx-background-insets"), 1);
    assertTrue(
        Double.parseDouble(padding[1]) >= gap + 4,
        "numbers must not touch the gutter's right edge");
    assertTrue(Double.parseDouble(padding[3]) >= 4, "numbers must not touch the window edge");
  }

  @Test
  void noThemeResetsTheGutterSpacing() {
    for (Rule rule : rules) {
      if (rule.selector().contains(".lineno") && !rule.selector().endsWith(".text")) {
        assertFalse(
            rule.declarations().containsKey("-fx-padding")
                && !rule.selector().equals(".code-area .lineno"),
            "only the base rule sets gutter padding: " + rule.selector());
        assertFalse(
            rule.declarations().containsKey("-fx-background-insets")
                && !rule.selector().equals(".code-area .lineno"),
            "only the base rule sets gutter insets: " + rule.selector());
      }
    }
  }

  // ---- helpers under test ----

  @Test
  void contrastOfBlackAndWhiteIs21() {
    assertEquals(21.0, contrast("#000000", "#ffffff"), 1e-9);
    assertEquals(21.0, contrast("#ffffff", "#000000"), 1e-9);
  }

  @Test
  void contrastOfIdenticalColoursIs1() {
    assertEquals(1.0, contrast("#767676", "#767676"), 1e-9);
  }

  @Test
  void parserLetsLaterRulesWinAndSplitsSelectorLists() {
    List<Rule> parsed = parse("/* c */ .a, .b { -fx-x: 1; } .a { -fx-x: 2; -fx-y: 3 }");
    assertEquals(3, parsed.size());
    assertEquals(".b", parsed.get(1).selector());
    assertEquals("2", parsed.get(2).declarations().get("-fx-x"));
    assertEquals("3", parsed.get(2).declarations().get("-fx-y"));
  }

  @Test
  void missingPropertyFailsLoudly() {
    assertThrows(
        AssertionError.class,
        () -> effective(List.of(""), ".no-such-selector", "-fx-background-color"));
  }

  // ---- implementation ----

  private static void assertHandleContrast(List<String> chain, String theme) {
    String track = effective(chain, ".scroll-bar > .track-background", "-fx-background-color");
    String thumb = effective(chain, ".scroll-bar > .thumb", "-fx-background-color");
    double ratio = contrast(thumb, track);
    assertTrue(
        ratio >= MIN_CONTRAST,
        theme + ": handle " + thumb + " on track " + track + " has contrast " + ratio);
  }

  /**
   * The value a property ends up with after applying the base rule and each theme prefix in order.
   */
  private static String effective(List<String> prefixes, String selector, String property) {
    String value = null;
    for (String prefix : prefixes) {
      String wanted = (prefix + selector).trim();
      for (Rule rule : rules) {
        if (rule.selector().equals(wanted) && rule.declarations().containsKey(property))
          value = rule.declarations().get(property);
      }
    }
    if (value == null) fail("no " + property + " for " + selector + " under " + prefixes);
    return value;
  }

  /**
   * The number at {@code indexFromLeft} in a space separated list, e.g. index 1 of "0 10 0 0" is
   * 10.
   */
  private static double lastNumber(String list, int indexFromLeft) {
    return Double.parseDouble(list.trim().split("\\s+")[indexFromLeft]);
  }

  static List<Rule> parse(String css) {
    String clean = css.replaceAll("(?s)/\\*.*?\\*/", " ");
    List<Rule> out = new ArrayList<>();
    Matcher m = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(clean);
    while (m.find()) {
      Map<String, String> declarations = new LinkedHashMap<>();
      for (String decl : m.group(2).split(";")) {
        int colon = decl.indexOf(':');
        if (colon > 0)
          declarations.put(decl.substring(0, colon).trim(), decl.substring(colon + 1).trim());
      }
      for (String selector : m.group(1).split(","))
        out.add(new Rule(selector.trim().replaceAll("\\s+", " "), declarations));
    }
    return out;
  }

  /** WCAG 2.x contrast ratio between two #rrggbb colours. */
  static double contrast(String a, String b) {
    double la = luminance(a), lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(String hex) {
    int rgb = Integer.parseInt(hex.trim().substring(1), 16);
    return 0.2126 * channel((rgb >> 16) & 0xff)
        + 0.7152 * channel((rgb >> 8) & 0xff)
        + 0.0722 * channel(rgb & 0xff);
  }

  private static double channel(int c) {
    double s = c / 255.0;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  }
}
