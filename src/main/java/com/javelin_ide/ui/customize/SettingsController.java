package com.javelin_ide.ui.customize;

import com.javelin_ide.icon.AppIcon;
import com.javelin_ide.settings.EditorPreferences;
import com.javelin_ide.settings.Settings;
import com.javelin_ide.settings.SettingsTemplates;
import com.javelin_ide.ui.SettingsDialog;
import com.javelin_ide.ui.editor.EditorTabs;
import com.javelin_ide.ui.shell.BottomPanel;
import com.javelin_ide.ui.shell.EditorListener;
import com.javelin_ide.ui.shell.KeyBinder;
import com.javelin_ide.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings, as the person sees them: the Settings menu, applying changes (theme, shortcuts, editor preferences, icon),
 * and reporting problems found in the settings files. Parts of the window that depend on settings register through
 * {@link #onApplied} instead of being called by name.
 */
public final class SettingsController implements EditorListener {
    private final Workbench bench;
    private final EditorTabs editors;
    private final BottomPanel bottom;
    private final ThemeController themes;
    private final KeyBinder binder;
    private final Runnable projectFilesChanged;
    private final List<Runnable> applied = new ArrayList<>();

    /** @param projectFilesChanged called when this controller creates a file inside the project (so the tree can show it) */
    public SettingsController(Workbench bench, EditorTabs editors, BottomPanel bottom, ThemeController themes, KeyBinder binder, Runnable projectFilesChanged) {
        this.bench = bench;
        this.editors = editors;
        this.bottom = bottom;
        this.themes = themes;
        this.binder = binder;
        this.projectFilesChanged = projectFilesChanged;
    }

    /** Runs {@code action} (in registration order) every time settings have been applied. */
    public void onApplied(Runnable action) { applied.add(action); }

    /** Re-applies everything derived from settings: editor preferences, theme, shortcuts, and whatever registered. */
    public void apply() {
        bench.reloadDerived();
        applyIcon();
        themes.refresh();
        themes.apply();
        binder.apply();
        applied.forEach(Runnable::run);
    }

    public void reload() {
        bench.settings().reload();
        apply();
        if (!reportProblems(true)) bench.status("Settings reloaded");
    }

    /** Writes problems found in the settings files to the Output panel. Returns true if there were any. */
    public boolean reportProblems(boolean reveal) {
        Settings settings = bench.settings();
        List<String> problems = new ArrayList<>(settings.warnings());
        problems.addAll(EditorPreferences.validate(settings));
        problems.addAll(bench.keys().problems());
        problems.addAll(binder.problems());
        for (String problem : problems) bottom.output().append("settings: " + problem);
        if (problems.isEmpty()) return false;
        bench.status(problems.size() + " settings problem(s); see Output");
        if (reveal) bottom.show(bottom.outputTab());
        return true;
    }

    public void openDialog() {
        if (SettingsDialog.showUser(bench.scene().getWindow(), bench.settings(), bench.keys(), themes.themes(), themes.current().id())) { apply(); bench.status("Settings saved"); }
    }

    public void openProjectDialog() {
        if (!bench.requireProject("Project Settings")) return;
        if (SettingsDialog.showProject(bench.scene().getWindow(), bench.settings())) { apply(); bench.status("Project settings saved"); }
    }

    /** Opens a settings file in an editor tab, first creating it from a fully commented template if it does not exist. */
    public void openFile(Settings.Scope scope) {
        Settings settings = bench.settings();
        Path path = scope == Settings.Scope.USER ? settings.userFile() : settings.projectFile();
        if (path == null) { bench.showError("Project Settings", "Open a project directory first."); return; }
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, scope == Settings.Scope.USER ? SettingsTemplates.user(bench.keys()) : SettingsTemplates.project());
                if (scope == Settings.Scope.PROJECT) projectFilesChanged.run();
            }
        } catch (IOException ex) { bench.showError("Could not create settings file", ex.getMessage()); return; }
        editors.openFile(path.toFile());
    }

    /** True for the settings files and custom theme stylesheets: saving one of them re-applies settings. */
    private boolean isSettingsPath(File file) {
        Settings settings = bench.settings();
        Path path = file.toPath().toAbsolutePath().normalize();
        if (path.equals(settings.userFile())) return true;
        if (settings.projectFile() != null && path.equals(settings.projectFile())) return true;
        return path.startsWith(settings.themesDirectory()) && path.toString().toLowerCase(Locale.ROOT).endsWith(".css");
    }

    @Override public void saved(File file, File previous, String text) { if (isSettingsPath(file)) reload(); }

    /**
     * Window, taskbar and Dock icon: the {@code app.icon} image when it can be read, otherwise the built-in one.
     * JavaFX's stage icons cover Windows and Linux; macOS takes its Dock icon from {@code java.awt.Taskbar}.
     */
    public void applyIcon() {
        AppIcon.Result icon = AppIcon.resolve(bench.settings().get(AppIcon.SETTING, ""));
        if (bench.stage() != null) {
            List<javafx.scene.image.Image> images = new ArrayList<>();
            for (var image : icon.images()) {
                try { images.add(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(AppIcon.png(image)))); }
                catch (IOException ex) { /* skip this size */ }
            }
            bench.stage().getIcons().setAll(images);
        }
        try {
            if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE))
                java.awt.Taskbar.getTaskbar().setIconImage(icon.images().get(icon.images().size() - 1));
        } catch (Throwable ignored) { /* no Dock/taskbar integration here: the stage icons still apply */ }
        if (icon.warning() != null) bench.status(icon.warning());
    }
}
