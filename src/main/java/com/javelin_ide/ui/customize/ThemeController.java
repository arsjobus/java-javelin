package com.javelin_ide.ui.customize;

import com.javelin_ide.settings.Settings;
import com.javelin_ide.settings.Themes;
import com.javelin_ide.settings.Themes.Theme;
import com.javelin_ide.ui.shell.Workbench;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Window;

/** Themes: the View > Theme menu, the stylesheets of the window, its dialogs and popups. */
public final class ThemeController {
    private static final String THEME_KEY = "theme";
    private final Workbench bench;
    private final Set<String> themeSheets = new HashSet<>();
    private final Menu themeMenu = new Menu("Theme");
    private List<Theme> themes = Themes.builtIn();
    private Theme theme = themes.get(0);

    public ThemeController(Workbench bench) { this.bench = bench; }

    public Menu menu() { return themeMenu; }
    public List<Theme> themes() { return themes; }
    public Theme current() { return theme; }

    /** BurpleNurple is the default look; other themes add style classes (and custom stylesheets) to the scene root. */
    public void install() {
        // Dialogs open in their own scene, so they do not inherit this window's stylesheet or theme class.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(this::styleDialog);
        });
        refresh();
        apply();
    }

    /** Re-reads the themes folder and picks the configured theme (falling back to the pre-v0.8 saved choice, then light). */
    public void refresh() {
        Settings settings = bench.settings();
        themes = Themes.available(settings.themesDirectory());
        theme = Themes.find(themes, settings.get(THEME_KEY, bench.preferences().get(THEME_KEY, Themes.DEFAULT_ID)));
        ToggleGroup group = new ToggleGroup();
        themeMenu.getItems().clear();
        for (Theme candidate : themes) {
            RadioMenuItem item = new RadioMenuItem(candidate.name());
            item.setToggleGroup(group);
            item.setSelected(candidate.id().equals(theme.id()));
            item.setOnAction(e -> choose(candidate));
            themeMenu.getItems().add(item);
        }
    }

    private void choose(Theme chosen) {
        bench.settings().put(Settings.Scope.USER, THEME_KEY, chosen.id());
        bench.saveUserSettings();
        theme = chosen;
        apply();
        bench.status("Theme: " + chosen.name());
    }

    public void apply() {
        Scene scene = bench.scene();
        scene.getStylesheets().removeIf(themeSheets::contains);
        if (theme.stylesheet() != null) {
            String url = theme.stylesheet().toUri().toString();
            themeSheets.add(url);
            scene.getStylesheets().add(url); // after editor.css, so a custom theme can override it
        }
        setThemeClass(scene.getRoot());
        Window.getWindows().forEach(this::styleDialog);
    }

    private void styleDialog(Window window) {
        Scene dialogScene = window.getScene();
        if (dialogScene == null || !(dialogScene.getRoot() instanceof DialogPane pane)) return;
        syncStylesheets(pane);
        setThemeClass(pane);
    }

    /** Gives a popup the window's stylesheets and theme class. */
    public void stylePopup(Parent root) {
        syncStylesheets(root);
        setThemeClass(root);
    }

    /** Gives a dialog or popup the scene's stylesheets, dropping custom-theme sheets that are no longer in use. */
    private void syncStylesheets(Parent target) {
        Scene scene = bench.scene();
        target.getStylesheets().removeIf(sheet -> themeSheets.contains(sheet) && !scene.getStylesheets().contains(sheet));
        for (String sheet : scene.getStylesheets()) if (!target.getStylesheets().contains(sheet)) target.getStylesheets().add(sheet);
    }

    private void setThemeClass(Parent root) {
        root.getStyleClass().removeAll(Themes.ALL_CLASSES);
        root.getStyleClass().addAll(theme.styleClasses());
    }
}
