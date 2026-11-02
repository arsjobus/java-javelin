package com.javelin.styles;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Themes;
import com.javelin.settings.Themes.Theme;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every built-in theme must keep its text readable. The style classes come from {@link Themes} (so the test follows
 * what the application really puts on the scene root) and the colours from the shipped {@code editor.css}, resolved
 * the way JavaFX does it by {@link CssCascade}. JavaFX-free, so it runs headless with the other core tests.
 *
 * <p>Light and dark need 4.5:1 (WCAG AA for text). High Contrast needs 7:1 (AAA), and must not merely be the dark
 * theme: its colours are pure black and white.
 */
class ThemeContrastTest {
    private static final double TEXT = 4.5;
    private static final double HIGH_CONTRAST_TEXT = 7.0;

    private static CssCascade css;

    @BeforeAll static void load() throws IOException { css = CssCascade.shipped(); }

    private static Theme theme(String id) { return Themes.find(Themes.builtIn(), id); }

    private static Set<String> root(Theme theme) {
        Set<String> classes = new java.util.LinkedHashSet<>(List.of("root"));
        classes.addAll(theme.styleClasses());
        return classes;
    }

    private static Set<String> popupRoot(Theme theme) {
        Set<String> classes = new java.util.LinkedHashSet<>(theme.styleClasses());
        classes.add("popup-pane");
        return classes;
    }

    private static double minimum(Theme theme) { return theme.id().equals("high-contrast") ? HIGH_CONTRAST_TEXT : TEXT; }

    /** One text colour on the surface it is drawn on, for a theme. */
    private record Pair(String what, String foreground, String background) {
        double ratio() { return CssCascade.contrast(foreground, background); }
    }

    private static Pair pair(String what, List<Set<String>> path, String fgProperty, String bgProperty) {
        return pair(what, path, fgProperty, path, bgProperty);
    }

    private static Pair pair(String what, List<Set<String>> fgPath, String fgProperty, List<Set<String>> bgPath, String bgProperty) {
        return new Pair(what, css.value(fgPath, fgProperty), css.value(bgPath, bgProperty));
    }

    private static List<Set<String>> path(Theme theme, Set<?>... below) {
        List<Set<String>> path = new ArrayList<>();
        path.add(root(theme));
        for (Set<?> node : below) path.add(new java.util.LinkedHashSet<>(node.stream().map(String::valueOf).toList()));
        return path;
    }

    private static List<Pair> pairs(Theme theme) {
        List<Pair> out = new ArrayList<>();
        List<Set<String>> editor = path(theme, Set.of("code-area"));
        String editorBackground = css.value(editor, "-fx-background-color");

        for (String token : List.of("", "keyword", "type", "string", "comment", "annotation", "number")) {
            Set<String> text = token.isEmpty() ? Set.of("text") : Set.of("text", token);
            out.add(new Pair("editor " + (token.isEmpty() ? "text" : token),
                css.value(path(theme, Set.of("code-area"), text), "-fx-fill"), editorBackground));
        }
        out.add(pair("line numbers", path(theme, Set.of("code-area"), Set.of("lineno"), Set.of("text")), "-fx-fill",
            path(theme, Set.of("code-area"), Set.of("lineno")), "-fx-background-color"));

        out.add(new Pair("problem error", css.value(path(theme, Set.of("problem-error")), "-fx-text-fill"), editorBackground));
        out.add(new Pair("problem warning", css.value(path(theme, Set.of("problem-warning")), "-fx-text-fill"), editorBackground));

        String terminalBackground = css.value(path(theme, Set.of("terminal-transcript"), Set.of("content")), "-fx-background-color");
        out.add(new Pair("terminal text", css.value(path(theme, Set.of("terminal-transcript"), Set.of("text")), "-fx-text-fill"), terminalBackground));
        out.add(new Pair("terminal input", css.value(path(theme, Set.of("terminal-input")), "-fx-text-fill"),
            css.value(path(theme, Set.of("terminal-input")), "-fx-background-color")));
        out.add(new Pair("terminal prompt text", css.value(path(theme, Set.of("terminal-input"), Set.of("prompt-text")), "-fx-fill"),
            css.value(path(theme, Set.of("terminal-input")), "-fx-background-color")));
        out.add(new Pair("virtualenv prompt", css.value(path(theme, Set.of("prompt-venv")), "-fx-text-fill"), terminalBackground));
        out.add(new Pair("terminal selection", css.value(path(theme, Set.of("terminal-transcript")), "-fx-highlight-text-fill"),
            css.value(path(theme, Set.of("terminal-transcript")), "-fx-highlight-fill")));

        // Looked-up colours the standard controls (labels, menus, lists, trees, text fields, the Output panel) derive from.
        List<Set<String>> rootOnly = path(theme);
        out.add(pair("labels and menus", rootOnly, "-fx-text-base-color", rootOnly, "-fx-background"));
        out.add(pair("labels on controls", rootOnly, "-fx-text-background-color", rootOnly, "-fx-background"));
        out.add(pair("text fields and Output panel", rootOnly, "-fx-text-inner-color", rootOnly, "-fx-control-inner-background"));
        out.add(pair("selected rows and menu items", rootOnly, "-fx-selection-bar-text", rootOnly, "-fx-selection-bar"));
        out.add(pair("text field selection", path(theme, Set.of("text-input", ":focused")), "-fx-highlight-text-fill",
            path(theme, Set.of("text-input", ":focused")), "-fx-highlight-fill"));
        out.add(new Pair("text field hint", css.value(path(theme, Set.of("text-input")), "-fx-prompt-text-fill"),
            css.value(rootOnly, "-fx-control-inner-background")));

        for (Set<String> state : List.of(Set.of("button"), Set.of("button", ":hover"), Set.of("button", ":pressed"), Set.of("button", ":focused"))) {
            out.add(pair("dialog button " + state, path(theme, Set.of("dialog-pane"), state), "-fx-text-fill",
                path(theme, Set.of("dialog-pane"), state), "-fx-background-color"));
        }

        List<Set<String>> popup = List.of(popupRoot(theme));
        String popupBackground = css.value(popup, "-fx-background-color");
        for (Set<String> cell : List.of(Set.of("list-cell", ":filled"), Set.of("list-cell", ":filled", ":selected"))) {
            List<Set<String>> cellPath = List.of(popupRoot(theme), cell);
            out.add(pair("completion popup " + cell, cellPath, "-fx-text-fill", cellPath, "-fx-background-color"));
        }
        out.add(new Pair("completion popup text", css.value(List.of(popupRoot(theme), Set.of("popup-text")), "-fx-text-fill"), popupBackground));
        return out;
    }

    private void assertReadable(String themeId) {
        Theme theme = theme(themeId);
        List<String> failures = new ArrayList<>();
        for (Pair p : pairs(theme)) {
            // A transparent cell background lets the popup's own background through; only compare real colours.
            if (!CssCascade.isHex(p.foreground()) || !CssCascade.isHex(p.background())) continue;
            if (p.ratio() < minimum(theme)) {
                failures.add(String.format("%s: %s on %s is %.2f:1, needs %.1f:1", p.what(), p.foreground(), p.background(), p.ratio(), minimum(theme)));
            }
        }
        assertTrue(failures.isEmpty(), themeId + " has unreadable text:\n" + String.join("\n", failures));
    }

    @Test void lightThemeTextIsReadable() { assertReadable("light"); }

    @Test void darkThemeTextIsReadable() { assertReadable("dark"); }

    @Test void highContrastTextMeetsAAA() { assertReadable("high-contrast"); }

    @Test void everyCheckedColourIsARealColour() {
        // Guards the test itself: if a rule is renamed so the lookup finds something other than a hex colour, the
        // contrast loop above would skip it silently. Only transparent popup cell backgrounds are allowed to skip.
        for (String id : List.of("light", "dark", "high-contrast")) {
            long compared = pairs(theme(id)).stream().filter(p -> CssCascade.isHex(p.foreground()) && CssCascade.isHex(p.background())).count();
            assertTrue(compared >= 25, id + " compared only " + compared + " colour pairs");
        }
    }

    // ---- High Contrast really is high contrast ----

    @Test void highContrastIsPureBlackAndWhite() {
        Theme hc = theme("high-contrast");
        List<Set<String>> editor = path(hc, Set.of("code-area"));
        assertEquals("#000000", css.value(editor, "-fx-background-color"));
        assertEquals("#ffffff", css.value(path(hc, Set.of("code-area"), Set.of("text")), "-fx-fill"));
        assertEquals("#000000", css.value(path(hc), "-fx-background"));
        assertEquals("#000000", css.value(path(hc), "-fx-control-inner-background"));
        assertEquals("#ffffff", css.value(path(hc), "-fx-text-base-color"));
        assertEquals("#ffffff", css.value(path(hc), "-fx-text-inner-color"));
        assertEquals("#000000", css.value(path(hc, Set.of("terminal-transcript"), Set.of("content")), "-fx-background-color"));
        assertEquals("#ffffff", css.value(path(hc, Set.of("terminal-transcript"), Set.of("text")), "-fx-text-fill"));
    }

    @Test void highContrastDiffersFromDarkInEveryPaletteColour() {
        Theme hc = theme("high-contrast"), dark = theme("dark");
        List<String> same = new ArrayList<>();
        for (String property : List.of("-fx-background", "-fx-control-inner-background", "-fx-text-base-color", "-fx-selection-bar")) {
            if (css.value(path(hc), property).equals(css.value(path(dark), property))) same.add(property);
        }
        for (String token : List.of("text", "keyword", "type", "string", "comment", "annotation", "number")) {
            Set<String> node = token.equals("text") ? Set.of("text") : Set.of("text", token);
            if (css.value(path(hc, Set.of("code-area"), node), "-fx-fill").equals(css.value(path(dark, Set.of("code-area"), node), "-fx-fill"))
                && !token.equals("annotation")) same.add("editor " + token);
        }
        assertTrue(same.isEmpty(), "High Contrast still resolves to the dark theme for: " + same);
    }

    @Test void highContrastHasWhiteBordersAndAYellowFocus() {
        Theme hc = theme("high-contrast");
        assertEquals("#ffffff", css.value(path(hc), "-fx-text-box-border"));
        assertEquals("#ffd866", css.value(path(hc), "-fx-focus-color"));
        assertEquals("#ffffff", css.value(List.of(popupRoot(hc)), "-fx-border-color"));
    }

    @Test void highContrastRulesComeAfterEveryDarkRule() {
        // Same specificity as the BurpleNurple Mocha rules, so only being later in the file makes High Contrast win.
        int lastDark = -1, firstHighContrast = Integer.MAX_VALUE;
        for (CssCascade.Rule rule : css.rules()) {
            String selector = rule.selector();
            if (selector.contains(":not(.theme-hc)") || selector.contains("scroll-bar")) continue; // scroll bars have their own guarded rules
            if (selector.contains(".theme-dark")) lastDark = Math.max(lastDark, rule.order());
            if (selector.contains(".theme-hc")) firstHighContrast = Math.min(firstHighContrast, rule.order());
        }
        assertTrue(lastDark >= 0 && firstHighContrast != Integer.MAX_VALUE, "both blocks must exist");
        assertTrue(firstHighContrast > lastDark, "High Contrast rules start at #" + firstHighContrast + " but a dark rule is at #" + lastDark + ": the dark theme would override them");
    }

    @Test void lightThemeKeepsItsBurpleNurpleSurfaces() {
        // The surfaces were right; only the text was wrong. Pin them so contrast fixes never repaint the backgrounds.
        Theme light = theme("light");
        assertEquals("#eff1f5", css.value(path(light, Set.of("code-area")), "-fx-background-color"));
        assertEquals("#e6e9ef", css.value(path(light, Set.of("code-area"), Set.of("lineno")), "-fx-background-color"));
        assertEquals("#eff1f5", css.value(path(light), "-fx-background"));
        assertEquals("#8839ef", css.value(path(light), "-fx-accent"));
        assertEquals("#8839ef", css.value(path(light, Set.of("dialog-pane"), Set.of("button")), "-fx-background-color"));
    }

    @Test void lightSyntaxColoursKeepTheirHue() {
        // Darkened for contrast, not replaced: keywords stay purple, strings green, numbers orange, types teal.
        Theme light = theme("light");
        assertEquals("#8839ef", css.value(path(light, Set.of("code-area"), Set.of("text", "keyword")), "-fx-fill"));
        assertTrue(dominant(css.value(path(light, Set.of("code-area"), Set.of("text", "string")), "-fx-fill")) == 'g');
        assertTrue(dominant(css.value(path(light, Set.of("code-area"), Set.of("text", "number")), "-fx-fill")) == 'r');
        assertTrue(dominant(css.value(path(light, Set.of("code-area"), Set.of("text", "type")), "-fx-fill")) != 'r');
    }

    private static char dominant(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return r >= g && r >= b ? 'r' : g >= b ? 'g' : 'b';
    }

    // ---- the cascade model itself ----

    @Test void laterRuleWinsAtEqualSpecificityButSpecificityBeatsOrder() {
        CssCascade c = CssCascade.parse(".a .b { -fx-x: 1; } .a .b { -fx-x: 2; } .a .b.c { -fx-x: 3; } .b { -fx-x: 4; }");
        assertEquals("2", c.value(List.of(Set.of("a"), Set.of("b")), "-fx-x"));
        assertEquals("3", c.value(List.of(Set.of("a"), Set.of("b", "c")), "-fx-x"));
        assertEquals("4", c.value(List.of(Set.of("b")), "-fx-x"));
    }

    @Test void notAndPseudoClassesAreHonoured() {
        CssCascade c = CssCascade.parse(".r .t { -fx-x: base; } .r:not(.hc) .t { -fx-x: plain; } .r .t:hover { -fx-x: hover; }");
        assertEquals("plain", c.value(List.of(Set.of("r"), Set.of("t")), "-fx-x"));
        assertEquals("base", c.value(List.of(Set.of("r", "hc"), Set.of("t")), "-fx-x"));
        assertEquals("hover", c.value(List.of(Set.of("r"), Set.of("t", ":hover")), "-fx-x"));
    }

    @Test void missingValueIsAnError() {
        assertThrows(AssertionError.class, () -> CssCascade.parse(".a { -fx-x: 1; }").value(List.of(Set.of("b")), "-fx-x"));
        assertTrue(CssCascade.parse(".a { -fx-x: 1; }").resolve(List.of(Set.of("b")), "-fx-x").isEmpty());
    }

    @Test void whatTheOldHighContrastBlockLookedLikeFailsTheOrderingRule() {
        // The bug: palette rules for .theme-dark placed after the .theme-hc ones silently replaced them.
        CssCascade buggy = CssCascade.parse(".theme-hc .t { -fx-fill: #ffffff; } .theme-dark .t { -fx-fill: #cdd6f4; }");
        assertEquals("#cdd6f4", buggy.value(List.of(Set.of("theme-dark", "theme-hc"), Set.of("t")), "-fx-fill"));
        CssCascade fixed = CssCascade.parse(".theme-dark .t { -fx-fill: #cdd6f4; } .theme-hc .t { -fx-fill: #ffffff; }");
        assertEquals("#ffffff", fixed.value(List.of(Set.of("theme-dark", "theme-hc"), Set.of("t")), "-fx-fill"));
    }
}
