package com.javelin_ide.settings;

import com.javelin_ide.io.FileIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Layered, human-readable settings: built-in defaults (supplied by the caller), then the user file, then the
 * project file. Both files use the same trivial format: UTF-8, one {@code key=value} per line, {@code #} starts
 * a comment line. Values are taken verbatim to the end of the line (no escapes, no continuation lines), so a
 * Windows path or a shell command can be written as-is.
 *
 * <p>Saving rewrites only the lines that changed: comments, blank lines, ordering and untouched entries are
 * preserved, so a file the user edits by hand stays theirs. Nothing here touches the network.
 *
 * <p>Some keys are read from the user file only ({@link #isUserOnly}): a project must not be able to change the
 * user's theme or keybindings just by being opened.
 */
public final class Settings {
    public enum Scope { USER, PROJECT }

    public static final String FILE_NAME = "settings.conf";
    public static final String PROJECT_DIRECTORY = ".javelin-ide";
    public static final String SEARCH_CASE_SENSITIVE = "search.caseSensitive";

    private final Path userFile;
    private Path projectFile;
    private final Map<String, String> user = new LinkedHashMap<>();
    private final Map<String, String> project = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public Settings(Path userFile) {
        this.userFile = userFile.toAbsolutePath().normalize();
        reload();
    }

    /** Where the user's settings live. {@code JAVALIN_IDE_CONFIG_DIR} overrides the platform default. */
    public static Path defaultUserDirectory() {
        String override = System.getenv("JAVALIN_IDE_CONFIG_DIR");
        if (override != null && !override.isBlank()) return Path.of(override);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "javelin-ide");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null && !appData.isBlank() ? Path.of(appData, "javelin-ide") : Path.of(home, "AppData", "Roaming", "javelin-ide");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "javelin-ide") : Path.of(home, ".config", "javelin-ide");
    }

    /** Keys that only the user file may set. */
    public static boolean isUserOnly(String key) {
        return key.equals("theme") || key.equals("app.icon") || key.equals(SEARCH_CASE_SENSITIVE) || key.startsWith("key.");
    }

    public Path userFile() { return userFile; }

    /** The project settings file, or null when no project is open. It may not exist yet. */
    public Path projectFile() { return projectFile; }

    /** Folder scanned for custom theme stylesheets. */
    public Path themesDirectory() { return userFile.getParent().resolve("themes"); }

    /** Points the project layer at {@code directory/.javelin-ide/settings.conf}, or clears it when null. */
    public void setProject(Path directory) {
        projectFile = directory == null ? null : directory.toAbsolutePath().normalize().resolve(PROJECT_DIRECTORY).resolve(FILE_NAME);
        reload();
    }

    /** Re-reads both files, discarding unsaved in-memory changes. Problems are collected in {@link #warnings()}. */
    public void reload() {
        warnings.clear();
        read(userFile, user, "user settings");
        if (projectFile == null) project.clear(); else read(projectFile, project, "project settings");
        for (var it = project.keySet().iterator(); it.hasNext();) {
            String key = it.next();
            if (isUserOnly(key)) {
                warnings.add("project settings: '" + key + "' is only read from the user settings file and was ignored");
                it.remove();
            }
        }
    }

    public List<String> warnings() { return List.copyOf(warnings); }

    /** The effective value: project, then user, then {@code fallback}. */
    public String get(String key, String fallback) {
        if (!isUserOnly(key)) {
            String value = project.get(key);
            if (value != null) return value;
        }
        String value = user.get(key);
        return value != null ? value : fallback;
    }

    public int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key, "").trim()); } catch (NumberFormatException ex) { return fallback; }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, "").trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    /** The value set in the user file, or null. */
    public String getUser(String key) { return user.get(key); }

    /** The value set in the project file, or null. */
    public String getProject(String key) { return project.get(key); }

    /** Keys set in the user file that start with {@code prefix}. */
    public List<String> userKeys(String prefix) {
        return user.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
    }

    /** Sets a value in memory; call {@link #save} to write it. */
    public void put(Scope scope, String key, String value) {
        checkKey(scope, key);
        target(scope).put(key, value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip());
    }

    /** Removes a value in memory; call {@link #save} to write it. */
    public void remove(Scope scope, String key) {
        if (scope == Scope.PROJECT && projectFile == null) return;
        target(scope).remove(key);
    }

    /**
     * Writes the scope's values to its file, changing only lines that differ. Does nothing when there are no
     * values and no file, so an untouched project never gains a settings folder.
     */
    public void save(Scope scope) throws IOException {
        Path file = scope == Scope.USER ? userFile : projectFile;
        if (file == null) throw new IOException("No project is open");
        Map<String, String> values = target(scope);
        List<String> existing = Files.isRegularFile(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        if (!existing.isEmpty() && existing.get(0).startsWith("\uFEFF")) existing.set(0, existing.get(0).substring(1));
        if (existing.isEmpty() && values.isEmpty()) return;

        List<String> out = new ArrayList<>();
        Set<String> written = new HashSet<>();
        for (String line : existing) {
            String text = line.strip();
            int eq = text.indexOf('=');
            if (text.isEmpty() || text.charAt(0) == '#' || eq <= 0) { out.add(line); continue; } // comments and unparseable lines stay as they are
            String key = text.substring(0, eq).strip();
            String current = text.substring(eq + 1).strip();
            String wanted = values.get(key);
            if (wanted == null || !written.add(key)) continue; // removed, or a duplicate of a line already kept
            out.add(wanted.equals(current) ? line : key + "=" + wanted);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) if (written.add(entry.getKey())) out.add(entry.getKey() + "=" + entry.getValue());

        FileIO.writeAtomic(file, out.isEmpty() ? "" : String.join("\n", out) + "\n");
    }

    private Map<String, String> target(Scope scope) { return scope == Scope.USER ? user : project; }

    private void checkKey(Scope scope, String key) {
        if (key == null || key.isBlank() || key.contains("=") || key.chars().anyMatch(Character::isWhitespace) || key.startsWith("#"))
            throw new IllegalArgumentException("Invalid settings key: " + key);
        if (scope == Scope.PROJECT) {
            if (projectFile == null) throw new IllegalStateException("No project is open");
            if (isUserOnly(key)) throw new IllegalArgumentException("'" + key + "' can only be set in the user settings");
        }
    }

    private void read(Path file, Map<String, String> into, String label) {
        into.clear();
        if (!Files.isRegularFile(file)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            warnings.add(label + ": could not read " + file + " (" + ex.getMessage() + ")");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            line = line.strip();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { warnings.add(label + " line " + (i + 1) + ": expected key=value, ignored"); continue; }
            String key = line.substring(0, eq).strip();
            if (key.isEmpty()) { warnings.add(label + " line " + (i + 1) + ": expected key=value, ignored"); continue; }
            if (into.containsKey(key)) warnings.add(label + " line " + (i + 1) + ": '" + key + "' is set more than once; the last value is used");
            into.put(key, line.substring(eq + 1).strip());
        }
    }
}
