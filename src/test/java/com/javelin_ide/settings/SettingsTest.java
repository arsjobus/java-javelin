package com.javelin_ide.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.settings.Settings.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
    @TempDir Path dir;

    private Path userFile() { return dir.resolve("user").resolve("settings.conf"); }

    private Settings settings() throws IOException {
        Files.createDirectories(userFile().getParent());
        return new Settings(userFile());
    }

    private void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test void defaultsApplyWhenNothingIsSet() throws IOException {
        Settings s = settings();
        assertEquals("fallback", s.get("a", "fallback"));
        assertEquals(7, s.getInt("n", 7));
        assertTrue(s.getBoolean("b", true));
        assertNull(s.getUser("a"));
        assertNull(s.getProject("a"));
        assertTrue(s.warnings().isEmpty());
        assertNull(s.projectFile());
        assertEquals(userFile(), s.userFile());
        assertEquals(userFile().getParent().resolve("themes"), s.themesDirectory());
    }

    @Test void parsesKeyValueCommentsAndVerbatimValues() throws IOException {
        write(userFile(), "\uFEFF# comment\n\n  a = 1  \nrun.command=C:\\Program Files\\x --flag=yes # not a comment\nempty=\n");
        Settings s = new Settings(userFile());
        assertEquals("1", s.get("a", ""));
        assertEquals("C:\\Program Files\\x --flag=yes # not a comment", s.get("run.command", ""));
        assertEquals("", s.get("empty", "x"));
        assertTrue(s.warnings().isEmpty());
    }

    @Test void typedAccessorsFallBackOnGarbage() throws IOException {
        write(userFile(), "n=abc\nm= 12 \nb=maybe\nc=TRUE\nd=False\n");
        Settings s = new Settings(userFile());
        assertEquals(3, s.getInt("n", 3));
        assertEquals(12, s.getInt("m", 3));
        assertTrue(s.getBoolean("b", true));
        assertFalse(s.getBoolean("b", false));
        assertTrue(s.getBoolean("c", false));
        assertFalse(s.getBoolean("d", true));
    }

    @Test void malformedLinesAndDuplicatesProduceWarnings() throws IOException {
        write(userFile(), "novalue\n=nokey\na=1\na=2\n");
        Settings s = new Settings(userFile());
        assertEquals("2", s.get("a", ""));
        List<String> w = s.warnings();
        assertEquals(3, w.size());
        assertTrue(w.get(0).contains("line 1") && w.get(1).contains("line 2") && w.get(2).contains("more than once"));
    }

    @Test void unreadableFileIsReportedNotThrown() throws IOException {
        Files.createDirectories(userFile().getParent());
        Files.write(userFile(), new byte[] {(byte) 0xC3, (byte) 0x28, '\n'}); // invalid UTF-8
        Settings s = new Settings(userFile());
        assertEquals(1, s.warnings().size());
        assertTrue(s.warnings().get(0).startsWith("user settings: could not read"));
        assertEquals("x", s.get("a", "x"));
    }

    @Test void projectOverridesUserButNotUserOnlyKeys() throws IOException {
        write(userFile(), "editor.tabSize=8\ntheme=dark\nkey.file.save=Ctrl+S\n");
        Path project = dir.resolve("proj");
        write(project.resolve(".javelin-ide/settings.conf"), "editor.tabSize=2\ntheme=light\nkey.file.save=none\n");
        Settings s = new Settings(userFile());
        s.setProject(project);
        assertEquals(project.toAbsolutePath().normalize().resolve(".javelin-ide").resolve("settings.conf"), s.projectFile());
        assertEquals(2, s.getInt("editor.tabSize", 0));
        assertEquals("dark", s.get("theme", ""));
        assertEquals("Ctrl+S", s.get("key.file.save", ""));
        assertEquals(2, s.warnings().size());
        assertTrue(s.warnings().stream().allMatch(w -> w.contains("only read from the user settings file")));
        assertNull(s.getProject("theme"));
        assertEquals("2", s.getProject("editor.tabSize"));
        s.setProject(null);
        assertEquals(8, s.getInt("editor.tabSize", 0));
        assertNull(s.projectFile());
    }

    @Test void userOnlyKeys() {
        assertTrue(Settings.isUserOnly("theme"));
        assertTrue(Settings.isUserOnly("key.anything"));
        assertTrue(Settings.isUserOnly("app.icon"));
        assertFalse(Settings.isUserOnly("editor.tabSize"));
        assertFalse(Settings.isUserOnly("themes"));
    }

    @Test void putValidatesKeysAndScope() throws IOException {
        Settings s = settings();
        for (String bad : new String[] {null, "", "  ", "a=b", "a b", "#x", "a\tb"})
            assertThrows(IllegalArgumentException.class, () -> s.put(Scope.USER, bad, "v"), String.valueOf(bad));
        assertThrows(IllegalStateException.class, () -> s.put(Scope.PROJECT, "a", "v"));
        s.setProject(dir.resolve("p"));
        assertThrows(IllegalArgumentException.class, () -> s.put(Scope.PROJECT, "theme", "dark"));
        assertThrows(IllegalArgumentException.class, () -> s.put(Scope.PROJECT, "key.x", "Ctrl+X"));
        s.put(Scope.USER, "theme", "dark");
        assertEquals("dark", s.getUser("theme"));
    }

    @Test void putFlattensNewlinesAndTrims() throws IOException {
        Settings s = settings();
        s.put(Scope.USER, "a", "  line1\r\nline2\n ");
        assertEquals("line1  line2", s.getUser("a"));
        s.put(Scope.USER, "b", null);
        assertEquals("", s.getUser("b"));
    }

    @Test void removeIsInMemoryUntilSavedAndIgnoredForMissingProject() throws IOException {
        write(userFile(), "a=1\n");
        Settings s = new Settings(userFile());
        s.remove(Scope.USER, "a");
        s.remove(Scope.PROJECT, "a");
        assertNull(s.getUser("a"));
        assertEquals("a=1\n", Files.readString(userFile()));
        s.reload();
        assertEquals("1", s.get("a", ""));
    }

    @Test void saveChangesOnlyWhatDifferedAndKeepsHandWrittenStructure() throws IOException {
        write(userFile(), "# my settings\n\nz = 9\nkeep=1\n  spaced = old\nbroken line\ngone=1\ndup=1\ndup=2\n");
        Settings s = new Settings(userFile());
        s.put(Scope.USER, "spaced", "new");
        s.put(Scope.USER, "fresh", "f");
        s.remove(Scope.USER, "gone");
        s.save(Scope.USER);
        assertEquals("# my settings\n\nz = 9\nkeep=1\nspaced=new\nbroken line\ndup=2\nfresh=f\n", Files.readString(userFile()));
        Settings again = new Settings(userFile());
        assertEquals("new", again.get("spaced", ""));
        assertEquals("2", again.get("dup", ""));
    }

    @Test void saveStripsABomAndCreatesDirectories() throws IOException {
        write(userFile(), "\uFEFFa=1\n");
        Settings s = new Settings(userFile());
        s.put(Scope.USER, "b", "2");
        s.save(Scope.USER);
        assertEquals("a=1\nb=2\n", Files.readString(userFile()));

        Settings fresh = new Settings(dir.resolve("new/deep/settings.conf"));
        fresh.put(Scope.USER, "a", "1");
        fresh.save(Scope.USER);
        assertEquals("a=1\n", Files.readString(dir.resolve("new/deep/settings.conf")));
    }

    @Test void savingNothingToNowhereCreatesNothingButEmptyingAnExistingFileWorks() throws IOException {
        Settings s = new Settings(dir.resolve("none/settings.conf"));
        s.save(Scope.USER);
        assertFalse(Files.exists(dir.resolve("none")));

        write(userFile(), "a=1\n");
        Settings t = new Settings(userFile());
        t.remove(Scope.USER, "a");
        t.save(Scope.USER);
        assertEquals("", Files.readString(userFile()));
    }

    @Test void projectSaveNeedsAProjectAndNeverCreatesLeftoverTempFiles() throws IOException {
        Settings s = settings();
        assertThrows(IOException.class, () -> s.save(Scope.PROJECT));
        Path project = dir.resolve("proj");
        s.setProject(project);
        s.put(Scope.PROJECT, "editor.tabSize", "2");
        s.save(Scope.PROJECT);
        Path folder = project.resolve(".javelin-ide");
        assertEquals("editor.tabSize=2\n", Files.readString(folder.resolve("settings.conf")));
        try (var files = Files.list(folder)) { assertEquals(1, files.count()); }
    }

    @Test void userKeysFiltersByPrefix() throws IOException {
        write(userFile(), "key.a=1\nkey.b=2\nother=3\n");
        assertEquals(List.of("key.a", "key.b"), new Settings(userFile()).userKeys("key."));
    }

    @Test void defaultUserDirectoryHonoursPlatformConventions() {
        String os = System.getProperty("os.name"), home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "/home/u");
            System.setProperty("os.name", "Mac OS X");
            assertEquals(Path.of("/home/u", "Library", "Application Support", "javelin-ide"), Settings.defaultUserDirectory());
            System.setProperty("os.name", "Windows 11");
            assertTrue(Settings.defaultUserDirectory().endsWith("javelin-ide"));
            System.setProperty("os.name", "Linux");
            Path linux = Settings.defaultUserDirectory();
            assertTrue(linux.endsWith("javelin-ide"));
        } finally { System.setProperty("os.name", os); System.setProperty("user.home", home); }
    }

    @Test void theConfigFolderVariableIsSpelledJavelinAndOutranksTheOldMisspelling() {
        assertEquals(Path.of("/custom"), Settings.defaultUserDirectory(Map.of("JAVELIN_IDE_CONFIG_DIR", "/custom")::get));
        assertEquals(Path.of("/old"), Settings.defaultUserDirectory(Map.of("JAVALIN_IDE_CONFIG_DIR", "/old")::get),
            "setups made with the misspelled name keep working");
        assertEquals(Path.of("/new"), Settings.defaultUserDirectory(Map.of("JAVELIN_IDE_CONFIG_DIR", "/new", "JAVALIN_IDE_CONFIG_DIR", "/old")::get));
        assertEquals("JAVELIN_IDE_CONFIG_DIR", Settings.CONFIG_DIR_VARIABLE);
    }

    @Test void blankVariablesAreIgnoredAndXdgAndAppDataStillApply() {
        String os = System.getProperty("os.name"), home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "/home/u");
            System.setProperty("os.name", "Linux");
            assertEquals(Path.of("/home/u", ".config", "javelin-ide"), Settings.defaultUserDirectory(Map.of("JAVELIN_IDE_CONFIG_DIR", "  ")::get));
            assertEquals(Path.of("/xdg", "javelin-ide"), Settings.defaultUserDirectory(Map.of("XDG_CONFIG_HOME", "/xdg")::get));
            System.setProperty("os.name", "Windows 11");
            assertEquals(Path.of("C:/roaming", "javelin-ide"), Settings.defaultUserDirectory(Map.of("APPDATA", "C:/roaming")::get));
        } finally { System.setProperty("os.name", os); System.setProperty("user.home", home); }
    }

    @Test void largeSettingsFilesLoadQuickly() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100_000; i++) text.append("key").append(i).append('=').append(i).append('\n');
        write(userFile(), text.toString());
        long start = System.nanoTime();
        Settings s = new Settings(userFile());
        assertEquals(99_999, s.getInt("key99999", -1));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000);
    }
}
