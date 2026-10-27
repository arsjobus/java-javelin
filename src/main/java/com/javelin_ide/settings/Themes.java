package com.javelin_ide.settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The available themes: built-in ones (defined in {@code editor.css} by style class) plus custom stylesheets the
 * user drops into the themes folder. A custom theme is just a CSS file; it may declare {@code /* base: dark *}{@code /}
 * near the top to also get the dark theme's styling underneath. JavaFX-free.
 */
public final class Themes {
    private Themes() {}

    public static final String DEFAULT_ID = "light"; // PurpleBurple's Catppuccin Latte palette
    /** Every style class a theme may put on a root node, so they can all be cleared before applying another. */
    public static final List<String> ALL_CLASSES = List.of("theme-dark", "theme-hc", "theme-custom");

    private static final Pattern BASE_DARK = Pattern.compile("/\\*\\s*base:\\s*dark\\s*\\*/", Pattern.CASE_INSENSITIVE);

    /** A theme. {@code stylesheet} is null for built-ins, whose rules ship inside {@code editor.css}. */
    public record Theme(String id, String name, boolean dark, List<String> styleClasses, Path stylesheet) {
        public boolean custom() { return stylesheet != null; }
    }

    public static List<Theme> builtIn() {
        return List.of(
            new Theme("light", "PurpleBurple (Light)", false, List.of(), null),
            new Theme("dark", "PurpleBurple (Dark)", true, List.of("theme-dark"), null),
            new Theme("high-contrast", "High Contrast", true, List.of("theme-dark", "theme-hc"), null));
    }

    /** Built-ins followed by the custom themes found in {@code themesDirectory}, which may not exist. */
    public static List<Theme> available(Path themesDirectory) { return available(themesDirectory, Files::list, Files::newInputStream); }

    /** File-system access, injectable so unreadable folders and stylesheets can be simulated in tests. */
    interface Lister { Stream<Path> list(Path directory) throws IOException; }
    interface Opener { InputStream open(Path file) throws IOException; }

    static List<Theme> available(Path themesDirectory, Lister lister, Opener opener) {
        List<Theme> themes = new ArrayList<>(builtIn());
        if (themesDirectory == null || !Files.isDirectory(themesDirectory)) return themes;
        List<Path> sheets;
        try (Stream<Path> files = lister.list(themesDirectory)) {
            sheets = files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException | RuntimeException ex) {
            return themes;
        }
        for (Path sheet : sheets) {
            String file = sheet.getFileName().toString();
            String id = file.substring(0, file.length() - ".css".length());
            if (id.isBlank() || themes.stream().anyMatch(t -> t.id().equalsIgnoreCase(id))) continue;
            boolean dark = declaresDarkBase(sheet, opener);
            List<String> classes = dark ? List.of("theme-dark", "theme-custom") : List.of("theme-custom");
            themes.add(new Theme(id, id, dark, classes, sheet.toAbsolutePath().normalize()));
        }
        return themes;
    }

    /** The theme with this id (case-insensitive), or the default theme when there is none. */
    public static Theme find(List<Theme> themes, String id) {
        for (Theme theme : themes) if (theme.id().equalsIgnoreCase(id == null ? "" : id.strip())) return theme;
        for (Theme theme : themes) if (theme.id().equals(DEFAULT_ID)) return theme;
        return themes.get(0);
    }

    private static boolean declaresDarkBase(Path sheet, Opener opener) {
        try (InputStream in = opener.open(sheet)) {
            String head = new String(in.readNBytes(1024), StandardCharsets.UTF_8);
            return BASE_DARK.matcher(head).find();
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
