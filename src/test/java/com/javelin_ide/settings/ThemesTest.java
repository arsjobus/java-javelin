package com.javelin_ide.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.settings.Themes.Theme;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemesTest {
    @TempDir Path dir;

    @Test void builtInThemes() {
        List<Theme> themes = Themes.builtIn();
        assertEquals(List.of("light", "dark", "high-contrast"), themes.stream().map(Theme::id).toList());
        assertFalse(themes.get(0).dark());
        assertTrue(themes.get(2).dark());
        assertTrue(themes.stream().noneMatch(Theme::custom));
        assertTrue(Themes.ALL_CLASSES.containsAll(themes.get(2).styleClasses()));
    }

    @Test void defaultThemeFamilyIsCalledBurpleNurple() {
        List<Theme> themes = Themes.builtIn();
        assertEquals("BurpleNurple (Light)", themes.get(0).name());
        assertEquals("BurpleNurple (Dark)", themes.get(1).name());
        assertTrue(themes.stream().noneMatch(t -> t.name().contains("PurpleBurple")));
        assertEquals("light", Themes.DEFAULT_ID, "renaming the theme keeps the saved id, so existing settings still work");
    }

    @Test void missingOrNullFolderGivesOnlyBuiltIns() {
        assertEquals(3, Themes.available(null).size());
        assertEquals(3, Themes.available(dir.resolve("nope")).size());
        assertEquals(3, Themes.available(dir).size());
    }

    @Test void customThemesAreSortedFilteredAndDetectDarkBase() throws IOException {
        Files.writeString(dir.resolve("Zebra.CSS"), ".root { }");
        Files.writeString(dir.resolve("night.css"), "/* base: DARK */ .root { }");
        Files.writeString(dir.resolve("alpha.css"), ".root { }");
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        Files.writeString(dir.resolve(".css"), "no name");
        Files.writeString(dir.resolve("Dark.css"), "clashes with the built-in id");
        Files.createDirectory(dir.resolve("folder.css"));
        List<Theme> themes = Themes.available(dir);
        assertEquals(List.of("light", "dark", "high-contrast", "alpha", "night", "Zebra"), themes.stream().map(Theme::id).toList());
        Theme night = themes.get(4);
        assertTrue(night.custom() && night.dark());
        assertEquals(List.of("theme-dark", "theme-custom"), night.styleClasses());
        assertEquals(dir.resolve("night.css").toAbsolutePath().normalize(), night.stylesheet());
        assertEquals(List.of("theme-custom"), themes.get(3).styleClasses());
    }

    @Test void darkBaseMustBeNearTheTop() throws IOException {
        Files.writeString(dir.resolve("late.css"), "x".repeat(2000) + "/* base: dark */");
        assertFalse(Themes.available(dir).get(3).dark());
    }

    @Test void findIsCaseInsensitiveAndFallsBackToLight() {
        List<Theme> themes = Themes.builtIn();
        assertEquals("dark", Themes.find(themes, " DARK ").id());
        assertEquals("light", Themes.find(themes, "nonsense").id());
        assertEquals("light", Themes.find(themes, null).id());
        assertEquals("only", Themes.find(List.of(new Theme("only", "Only", false, List.of(), null)), "x").id());
    }

    @Test void aFolderOrStylesheetThatFailsToReadIsSkippedNotFatal() throws IOException {
        Files.writeString(dir.resolve("broken.css"), "/* base: dark */");
        assertEquals(3, Themes.available(dir, d -> { throw new IOException("denied"); }, Files::newInputStream).size());
        assertEquals(3, Themes.available(dir, d -> { throw new SecurityException("denied"); }, Files::newInputStream).size());
        List<Theme> themes = Themes.available(dir, Files::list, f -> { throw new IOException("vanished"); });
        assertEquals(4, themes.size());
        assertFalse(themes.get(3).dark(), "an unreadable stylesheet is treated as light");
    }

    @Test void unreadableFolderOrStylesheetDegradesGracefully() throws IOException {
        Path file = dir.resolve("a-file");
        Files.writeString(file, "x");
        assertEquals(3, Themes.available(file).size());
    }
}
