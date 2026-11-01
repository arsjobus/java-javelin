package com.javelin_ide.ui.shell;

import com.javelin_ide.io.ProjectPaths;
import com.javelin_ide.settings.EditorPreferences;
import com.javelin_ide.settings.Keybindings;
import com.javelin_ide.settings.NameList;
import com.javelin_ide.settings.Settings;
import com.javelin_ide.ui.Dialogs;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/**
 * What every part of one window shares: the status line, the settings, which project is open and the values derived
 * from settings. It holds state and a few helpers, never behaviour of one feature; features reach each other through
 * the editor, not through here.
 */
public final class Workbench {
    public static final String DEFAULT_TREE_EXCLUDES = ".git,target";

    private final Label status = new Label("Ready");
    private final Preferences preferences;
    // v0.8 customization: plain-file settings (see CUSTOMIZATION.md). The java.util.prefs store still holds
    // recent projects, the last-used commands and, as a fallback for existing installs, the old theme choice.
    private final Settings settings = new Settings(Settings.defaultUserDirectory().resolve(Settings.FILE_NAME));
    private final Keybindings keys = new Keybindings(settings);
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "recovery-writer"); t.setDaemon(true); return t; });
    private EditorPreferences editorPrefs = EditorPreferences.from(settings);
    private Set<String> treeExcludes = NameList.parse(settings.get("tree.exclude", DEFAULT_TREE_EXCLUDES));
    private int zoom; // temporary font-size offset for this session; never saved
    private File projectDirectory;
    private Stage stage;
    private Scene scene;

    public Workbench(Preferences preferences) { this.preferences = preferences; }

    /** Called once by the application when the window exists. */
    public void attach(Stage stage, Scene scene) { this.stage = stage; this.scene = scene; }

    public Label statusLabel() { return status; }
    public void status(String text) { status.setText(text); }
    public String statusText() { return status.getText(); }
    public Preferences preferences() { return preferences; }
    public Settings settings() { return settings; }
    public Keybindings keys() { return keys; }
    public Stage stage() { return stage; }
    public Scene scene() { return scene; }

    /** One background thread for small disk jobs (crash-recovery copies, pruning old state) that must not block the window. */
    public ExecutorService background() { return background; }

    public File projectDirectory() { return projectDirectory; }
    public void projectDirectory(File directory) { this.projectDirectory = directory; }

    public EditorPreferences editorPrefs() { return editorPrefs; }
    public Set<String> treeExcludes() { return treeExcludes; }
    public int zoom() { return zoom; }
    public void zoom(int zoom) { this.zoom = zoom; }

    /** Re-reads the values derived from the settings files (editor preferences, folders hidden from the tree). */
    public void reloadDerived() {
        editorPrefs = EditorPreferences.from(settings);
        treeExcludes = NameList.parse(settings.get("tree.exclude", DEFAULT_TREE_EXCLUDES));
    }

    public void saveUserSettings() {
        try { settings.save(Settings.Scope.USER); } catch (IOException ex) { showError("Could not save settings", ex.getMessage()); }
    }

    public String displayPath(File file) { return ProjectPaths.display(projectDirectory, file); }

    public boolean requireProject(String title) {
        if (projectDirectory != null) return true;
        showError(title, "Open a project directory first.");
        return false;
    }

    public void showError(String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR); a.setTitle(title); a.setHeaderText(null); a.setContentText(message == null ? "Unknown error." : message); Dialogs.show(a);
    }
}
