package com.javelin_ide.styles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small model of how JavaFX picks a value from {@code editor.css}, without starting JavaFX: of the rules that match
 * a node, the one with the highest specificity wins, and at equal specificity the one that comes later in the file.
 * That second rule is exactly what once made the High Contrast theme look like the dark theme, so tests that check
 * colours must go through this class instead of looking up a single selector by name.
 *
 * <p>A node is described by the set of its style classes, with pseudo-classes written with a leading colon
 * ({@code Set.of("list-cell", ":filled", ":selected")}); a path is the chain from the scene root down to the node.
 * Child ({@code >}) and descendant combinators are both treated as "somewhere below", which is all the theme rules need.
 */
final class CssCascade {
    record Rule(String selector, Map<String, String> declarations, int order) {}

    record Hit(String value, String selector) {}

    private static final Pattern PART = Pattern.compile("\\.[\\w-]+|:[\\w-]+");
    private static final Pattern NOT = Pattern.compile(":not\\(\\.([\\w-]+)\\)");

    private final List<Rule> rules;

    private CssCascade(List<Rule> rules) { this.rules = rules; }

    /** The stylesheet that ships with the application. */
    static CssCascade shipped() throws IOException {
        try (InputStream in = CssCascade.class.getResourceAsStream("/styles/editor.css")) {
            if (in == null) throw new IOException("editor.css must be on the classpath");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static CssCascade parse(String css) {
        String clean = css.replaceAll("(?s)/\\*.*?\\*/", " ");
        List<Rule> out = new ArrayList<>();
        Matcher block = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(clean);
        int order = 0;
        while (block.find()) {
            Map<String, String> declarations = new LinkedHashMap<>();
            for (String declaration : block.group(2).split(";")) {
                int colon = declaration.indexOf(':');
                if (colon > 0) declarations.put(declaration.substring(0, colon).trim(), declaration.substring(colon + 1).trim());
            }
            for (String selector : block.group(1).split(",")) out.add(new Rule(selector.trim().replaceAll("\\s+", " "), declarations, order++));
        }
        return new CssCascade(out);
    }

    List<Rule> rules() { return rules; }

    /** The value {@code property} ends up with on the last node of {@code path}, or empty when no rule sets it. */
    Optional<Hit> resolve(List<Set<String>> path, String property) {
        Rule best = null;
        for (Rule rule : rules) {
            if (!rule.declarations().containsKey(property) || !matches(rule.selector(), path)) continue;
            if (best == null || specificity(rule.selector()) > specificity(best.selector())
                || (specificity(rule.selector()) == specificity(best.selector()) && rule.order() > best.order())) best = rule;
        }
        return best == null ? Optional.empty() : Optional.of(new Hit(best.declarations().get(property), best.selector()));
    }

    /** Like {@link #resolve} but a missing property is a test failure that says what was looked up. */
    String value(List<Set<String>> path, String property) {
        return resolve(path, property).map(Hit::value)
            .orElseThrow(() -> new AssertionError("no " + property + " reaches " + path));
    }

    static int specificity(String selector) {
        int count = 0;
        Matcher notMatcher = NOT.matcher(selector);
        while (notMatcher.find()) count++;
        Matcher part = PART.matcher(NOT.matcher(selector).replaceAll(""));
        while (part.find()) count++;
        return count;
    }

    private static boolean matches(String selector, List<Set<String>> path) {
        String[] compounds = selector.replace(">", " ").trim().split("\\s+");
        if (!matchesCompound(compounds[compounds.length - 1], path.get(path.size() - 1))) return false;
        int ancestor = path.size() - 2;
        for (int i = compounds.length - 2; i >= 0; i--) {
            while (ancestor >= 0 && !matchesCompound(compounds[i], path.get(ancestor))) ancestor--;
            if (ancestor < 0) return false;
            ancestor--;
        }
        return true;
    }

    private static boolean matchesCompound(String compound, Set<String> node) {
        Matcher not = NOT.matcher(compound);
        while (not.find()) if (node.contains(not.group(1))) return false;
        Matcher part = PART.matcher(NOT.matcher(compound).replaceAll(""));
        while (part.find()) {
            String token = part.group();
            if (!node.contains(token.startsWith(".") ? token.substring(1) : token)) return false;
        }
        return true;
    }

    static boolean isHex(String value) { return value != null && value.matches("#[0-9a-fA-F]{6}"); }

    /** WCAG 2.x contrast ratio between two #rrggbb colours. */
    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.trim().substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xff) + 0.7152 * channel((rgb >> 8) & 0xff) + 0.0722 * channel(rgb & 0xff);
    }

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
