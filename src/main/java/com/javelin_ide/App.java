package com.javelin_ide;

import com.javelin_ide.build.BuildSystem;
import com.javelin_ide.editor.Indentation;
import com.javelin_ide.editor.SyntaxHighlighter;
import com.javelin_ide.editor.TextSearch;
import com.javelin_ide.icon.AppIcon;
import com.javelin_ide.io.CrashLog;
import com.javelin_ide.io.FileIO;
import com.javelin_ide.io.ImageFiles;
import com.javelin_ide.io.ProjectFiles;
import com.javelin_ide.io.ProjectWatcher;
import com.javelin_ide.io.RecoveryStore;
import com.javelin_ide.build.ManagedProcess;
import com.javelin_ide.build.Problem;
import com.javelin_ide.build.ProblemParser;
import com.javelin_ide.build.ProcessManager;
import com.javelin_ide.build.ShellCommand;
import com.javelin_ide.language.DiagnosticShifter;
import com.javelin_ide.language.Languages;
import com.javelin_ide.language.LanguageService;
import com.javelin_ide.language.Lsp;
import com.javelin_ide.settings.DefaultShortcuts;
import com.javelin_ide.settings.EditorPreferences;
import com.javelin_ide.settings.Keybindings;
import com.javelin_ide.settings.Settings;
import com.javelin_ide.settings.SettingsTemplates;
import com.javelin_ide.settings.Themes;
import com.javelin_ide.settings.Themes.Theme;
import com.javelin_ide.launch.LaunchArguments;
import com.javelin_ide.ui.CompletionPopup;
import com.javelin_ide.ui.Dialogs;
import com.javelin_ide.ui.HoverPopup;
import com.javelin_ide.ui.ImageViewerPane;
import com.javelin_ide.ui.OutputPanel;
import com.javelin_ide.ui.ProblemsPanel;
import com.javelin_ide.ui.ReferencesPanel;
import com.javelin_ide.ui.SettingsDialog;
import com.javelin_ide.ui.Shortcuts;
import com.javelin_ide.ui.TerminalsPanel;
import com.javelin_ide.ui.TreeGestures;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CaretNode;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional;
import org.reactfx.Subscription;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;

public final class App extends Application {
    private static final int MAX_RECENT_PROJECTS = 8;
    private static final String THEME_KEY = "theme";
    private static final String DEFAULT_TREE_EXCLUDES = ".git,target";
    private final TabPane tabs = new TabPane();
    private final TreeView<File> projectTree = new TreeView<>();
    private final ContextMenu treeMenu = new ContextMenu();
    private final TreeGestures treeGestures = new TreeGestures();
    private final Label status = new Label("Ready");
    private final Preferences preferences = Preferences.userNodeForPackage(App.class);
    private final Map<Tab, Boolean> dirty = new HashMap<>();
    private final Map<Tab, Subscription> subscriptions = new HashMap<>();
    private final Map<CodeArea, List<CaretNode>> extraCarets = new HashMap<>();
    private final ProcessManager processes = new ProcessManager();
    private final OutputPanel output = new OutputPanel();
    private final ProblemsPanel problems = new ProblemsPanel();
    private final TerminalsPanel terminals = new TerminalsPanel(processes, () -> this.projectDirectory);
    private final Tab outputTab = new Tab("Output", output);
    private final Tab problemsTab = new Tab("Problems (0)", problems);
    private final Tab terminalTab = new Tab("Terminal", terminals);
    private final ReferencesPanel references = new ReferencesPanel();
    private final Tab referencesTab = new Tab("References", references);
    private final TabPane bottomTabs = new TabPane(outputTab, problemsTab, referencesTab, terminalTab);
    private final Label languageStatus = new Label();
    private final CompletionPopup completionPopup = new CompletionPopup();
    private final HoverPopup hoverPopup = new HoverPopup();
    private final Map<File, List<Lsp.Diagnostic>> diagnostics = new HashMap<>();
    private final LanguageService language = new LanguageService(this::onDiagnostics, this::onLanguageLog, () -> Platform.runLater(this::updateLanguageStatus));
    private final SplitPane editorSplit = new SplitPane(tabs);
    private static final long TREE_WATCH_MILLIS = 2000;
    // Notices files added, removed, renamed or moved outside the editor (terminal, build, git, file manager).
    private final ProjectWatcher treeWatcher = new ProjectWatcher(() -> this.treeExcludes, () -> Platform.runLater(this::refreshProjectTree));
    private File projectDirectory;
    private Scene scene;
    // v0.8 customization: plain-file settings (see CUSTOMIZATION.md). The java.util.prefs store still holds
    // recent projects, the last-used commands and, as a fallback for existing installs, the old theme choice.
    private final Settings settings = new Settings(Settings.defaultUserDirectory().resolve(Settings.FILE_NAME));
    private final Keybindings keys = new Keybindings(settings);
    private final Map<String, MenuItem> menuItems = new LinkedHashMap<>();
    private final List<String> keyProblems = new ArrayList<>();
    private final Set<String> themeSheets = new HashSet<>();
    private final Menu themeMenu = new Menu("Theme");
    private List<Theme> themes = Themes.builtIn();
    private Theme theme = themes.get(0);
    private EditorPreferences editorPrefs = EditorPreferences.from(settings);
    private Set<String> treeExcludes = parseNames(settings.get("tree.exclude", DEFAULT_TREE_EXCLUDES));
    private int zoom; // temporary font-size offset for this session; never saved
    private static final int AUTOSAVE_SECONDS = 5;
    private final RecoveryStore recovery = new RecoveryStore(settings.userFile().getParent().resolve("recovery"));
    private final Map<Tab, String> recoveryIds = new HashMap<>();
    private final java.util.concurrent.ExecutorService recoveryWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "recovery-writer"); t.setDaemon(true); return t; });
    private javafx.animation.Timeline autosave;
    private Stage primaryStage;

    @Override public void start(Stage stage) {
        CrashLog.install(settings.userFile().getParent().resolve("crashes"), report -> Platform.runLater(() ->
            status.setText(report == null ? "An unexpected error occurred." : "An unexpected error occurred; a report was saved to " + report)));
        this.primaryStage = stage;
        Dialogs.setOwner(stage);
        stage.setTitle("Javelin");
        applyIcon();
        projectTree.setShowRoot(true);
        projectTree.setCellFactory(tree -> new TreeCell<>() {
            {   // right-clicking a row selects it first, so the menu always acts on the row under the pointer
                setOnContextMenuRequested(event -> { if (!isEmpty() && getTreeItem() != null) projectTree.getSelectionModel().select(getTreeItem()); });
                // Double-click opens a file. It is decided from the presses on this very row, not from the tree's
                // selection or JavaFX's click count, so a shaky hand, a drag or a tree rebuild cannot lose or redirect it.
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (event.getButton() != MouseButton.PRIMARY) return;
                    File row = isEmpty() ? null : getItem();
                    boolean doubleClick = treeGestures.press(row, System.currentTimeMillis(), event.getScreenX(), event.getScreenY());
                    if (doubleClick && row.isFile() && getTreeItem() != null) {
                        projectTree.getSelectionModel().select(getTreeItem());
                        openFile(row);
                    }
                });
                // Drag a row onto a folder (or onto a file, meaning the folder it is in) to move it there.
                setOnDragDetected(event -> {
                    File file = getItem();
                    if (isEmpty() || file == null || file.equals(projectDirectory)) return;
                    // A click with a slightly moving pointer is not a drag; starting one would swallow the click.
                    if (!treeGestures.dragIntended(event.getScreenX(), event.getScreenY())) return;
                    Dragboard board = startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent(); content.putString(file.getAbsolutePath());
                    board.setContent(content); event.consume();
                });
                setOnDragOver(event -> { if (dropTarget(event.getDragboard(), getItem()) != null) event.acceptTransferModes(TransferMode.MOVE); event.consume(); });
                setOnDragDropped(event -> {
                    File source = dragSource(event.getDragboard());
                    File folder = dropTarget(event.getDragboard(), getItem());
                    boolean done = source != null && folder != null && moveInTree(source, folder);
                    event.setDropCompleted(done); event.consume();
                });
            }
            @Override protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getName());
                setContextMenu(empty || file == null ? null : treeMenu);
            }
        });
        MenuItem newFile = new MenuItem("New File..."), newFolder = new MenuItem("New Folder..."), rename = new MenuItem("Rename..."), delete = new MenuItem("Delete...");
        newFile.setOnAction(e -> createInTree(false));
        newFolder.setOnAction(e -> createInTree(true));
        rename.setOnAction(e -> renameInTree());
        delete.setOnAction(e -> deleteFromTree());
        treeMenu.getItems().addAll(newFile, newFolder, new SeparatorMenuItem(), rename, delete);
        // Right-clicking the empty part of the sidebar adds to the project folder itself.
        MenuItem rootFile = new MenuItem("New File..."), rootFolder = new MenuItem("New Folder...");
        rootFile.setOnAction(e -> { projectTree.getSelectionModel().clearSelection(); createInTree(false); });
        rootFolder.setOnAction(e -> { projectTree.getSelectionModel().clearSelection(); createInTree(true); });
        projectTree.setContextMenu(new ContextMenu(rootFile, rootFolder));
        projectTree.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.F2) { renameInTree(); event.consume(); return; } if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE && event.isShortcutDown()) { deleteFromTree(); event.consume(); } });

        Label projectLabel = new Label("PROJECT");
        projectLabel.setStyle("-fx-font-weight: bold;");
        VBox sidebar = new VBox(8, projectLabel, projectTree);
        sidebar.setPadding(new Insets(10));
        sidebar.setPrefWidth(250);
        VBox.setVgrow(projectTree, Priority.ALWAYS);

        BorderPane root = new BorderPane();

        root.setTop(createMenuBar(stage));
        root.setLeft(sidebar);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        editorSplit.setOrientation(Orientation.VERTICAL);
        problems.setOnOpen(this::openProblem);
        references.setOnOpen(r -> openFile(r.file(), r.line(), r.column()));
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) if (change.wasRemoved()) change.getRemoved().forEach(this::forgetTab);
        });
        problems.items().addListener((ListChangeListener<Problem>) c -> problemsTab.setText("Problems (" + problems.items().size() + ")"));
        root.setCenter(editorSplit);
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(status, statusSpacer, languageStatus);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(statusBar);
        scene = new Scene(root, 1200, 760);
        var css = getClass().getResource("/styles/editor.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        installTheme();
        applyKeybindings();
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> { if (!confirmCloseDirtyTabs()) event.consume(); else if (autosave != null) autosave.stop(); });
        stage.show();
        treeWatcher.start(TREE_WATCH_MILLIS);
        reportSettingsProblems(false);
        startCrashRecovery();
        openFromCommandLine();
    }

    // ---- started from the terminal: `javelin [directory]` (see packaging/macos) ----

    /**
     * Opens the directory given on the command line. The {@code javelin} command starts a new instance of this app for
     * every folder ({@code open -n}), so each window has exactly one project and nothing is handed to a running window.
     */
    private void openFromCommandLine() {
        LaunchArguments.Result launch = LaunchArguments.parse(getParameters().getRaw(), Path.of("").toAbsolutePath());
        launch.problem().ifPresent(status::setText);
        launch.directory().ifPresent(directory -> openProjectDirectory(directory.toFile()));
    }

    /** Creates a menu item whose shortcut can be rebound: its id and default are registered with {@link #keys}. */
    private MenuItem bound(String menu, String text, String id, String defaultBinding, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        keys.register(id, menu + " > " + text.replace("...", ""), defaultBinding);
        menuItems.put(id, item);
        item.setAccelerator(Shortcuts.parse(keys.binding(id)));
        return item;
    }

    private MenuBar createMenuBar(Stage stage) {
        Menu file = new Menu("File");
        file.getItems().addAll(
            bound("File", "New", "file.new", "", this::newDocument),
            bound("File", "Open File...", "file.openFile", "", () -> chooseAndOpenFile(stage)),
            bound("File", "Open Directory...", "file.openDirectory", "", () -> chooseProjectDirectory(stage)),
            createRecentProjectsMenu(), new SeparatorMenuItem(),
            bound("File", "Save", "file.save", DefaultShortcuts.SAVE, () -> saveCurrent(false)),
            bound("File", "Save As...", "file.saveAs", DefaultShortcuts.SAVE_AS, () -> saveCurrent(true)),
            bound("File", "Close", "file.close", "", this::closeCurrentTab), new SeparatorMenuItem(),
            bound("File", "Exit", "file.exit", "", () -> stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST))));

        Menu edit = new Menu("Edit");
        edit.getItems().addAll(
            bound("Edit", "Undo", "edit.undo", "", () -> currentEditor().ifPresent(CodeArea::undo)),
            bound("Edit", "Redo", "edit.redo", "", () -> currentEditor().ifPresent(CodeArea::redo)), new SeparatorMenuItem(),
            bound("Edit", "Search", "edit.search", DefaultShortcuts.SEARCH, this::search),
            bound("Edit", "Find Next", "edit.findNext", DefaultShortcuts.FIND_NEXT, this::findNext),
            bound("Edit", "Find Previous", "edit.findPrevious", DefaultShortcuts.FIND_PREVIOUS, this::findPrevious),
            bound("Edit", "Replace", "edit.replace", DefaultShortcuts.REPLACE, this::replace),
            bound("Edit", "Go to Line", "edit.goToLine", DefaultShortcuts.GO_TO_LINE, this::goToLine),
            bound("Edit", "Go to File", "edit.goToFile", DefaultShortcuts.GO_TO_FILE, this::goToFile),
            bound("Edit", "Match Bracket", "edit.matchBracket", DefaultShortcuts.MATCH_BRACKET, this::matchBracket),
            bound("Edit", "Add Cursor", "edit.addCursor", DefaultShortcuts.ADD_CURSOR, this::addCursor));

        Menu view = new Menu("View");
        view.getItems().addAll(
            bound("View", "Focus Mode", "view.focusMode", "", () -> { Node node = projectTree.getParent(); if (node != null) { boolean visible = node.isVisible(); node.setVisible(!visible); node.setManaged(!visible); } }),
            new SeparatorMenuItem(),
            bound("View", "Fold Selected", "view.fold", "", () -> currentEditor().ifPresent(CodeArea::foldSelectedParagraphs)),
            bound("View", "Unfold Current", "view.unfold", "", () -> currentEditor().ifPresent(a -> a.unfoldParagraphs(a.getCurrentParagraph()))),
            new SeparatorMenuItem(),
            bound("View", "Toggle Bottom Panel", "view.bottomPanel", DefaultShortcuts.BOTTOM_PANEL, this::toggleBottomPanel),
            bound("View", "Output", "view.output", "", () -> showBottom(outputTab)),
            bound("View", "Problems", "view.problems", "", () -> showBottom(problemsTab)),
            bound("View", "References", "view.references", "", () -> showBottom(referencesTab)),
            bound("View", "Terminal", "view.terminal", DefaultShortcuts.TERMINAL, () -> { showBottom(terminalTab); terminals.focusInput(); }),
            bound("View", "New Terminal", "view.newTerminal", DefaultShortcuts.NEW_TERMINAL, () -> { showBottom(terminalTab); terminals.newTerminal(); }),
            new SeparatorMenuItem(),
            bound("View", "Zoom In", "view.zoomIn", DefaultShortcuts.ZOOM_IN, () -> zoomEditors(1)),
            bound("View", "Zoom Out", "view.zoomOut", DefaultShortcuts.ZOOM_OUT, () -> zoomEditors(-1)),
            bound("View", "Reset Zoom", "view.zoomReset", DefaultShortcuts.ZOOM_RESET, () -> zoomEditors(0)),
            new SeparatorMenuItem(), themeMenu);

        Menu languageMenu = new Menu("Language");
        languageMenu.getItems().addAll(
            bound("Language", "Start Language Server...", "language.startServer", "", this::startLanguageServer),
            bound("Language", "Stop Language Server", "language.stopServer", "", this::stopLanguageServer),
            new SeparatorMenuItem(),
            bound("Language", "Complete", "language.complete", DefaultShortcuts.COMPLETE, this::complete),
            bound("Language", "Show Hover Info", "language.hover", DefaultShortcuts.HOVER, this::hover),
            bound("Language", "Signature Help", "language.signatureHelp", DefaultShortcuts.SIGNATURE_HELP, this::signatureHelp),
            bound("Language", "Go to Definition", "language.goToDefinition", DefaultShortcuts.GO_TO_DEFINITION, this::goToDefinition),
            bound("Language", "Find References", "language.findReferences", DefaultShortcuts.FIND_REFERENCES, this::findReferences),
            bound("Language", "Document Symbols", "language.documentSymbols", DefaultShortcuts.DOCUMENT_SYMBOLS, this::documentSymbols),
            new SeparatorMenuItem(),
            bound("Language", "Rename Symbol...", "language.rename", DefaultShortcuts.RENAME, this::renameSymbol),
            bound("Language", "Format Document", "language.format", DefaultShortcuts.FORMAT, this::formatDocument));

        Menu run = new Menu("Run");
        run.getItems().addAll(
            bound("Run", "Build", "run.build", DefaultShortcuts.BUILD, this::build),
            bound("Run", "Run Command...", "run.runCommand", DefaultShortcuts.RUN_COMMAND, this::runCommandDialog),
            new SeparatorMenuItem(),
            bound("Run", "Stop All Processes", "run.stopAll", DefaultShortcuts.STOP_ALL, this::stopAllProcesses),
            bound("Run", "Processes...", "run.processes", "", this::showProcesses));

        Menu settingsMenu = new Menu("Settings");
        settingsMenu.getItems().addAll(
            bound("Settings", "Settings...", "settings.open", DefaultShortcuts.SETTINGS, this::openSettingsDialog),
            bound("Settings", "Project Settings...", "settings.project", "", this::openProjectSettingsDialog),
            new SeparatorMenuItem(),
            bound("Settings", "Open User Settings File", "settings.openUserFile", "", () -> openSettingsFile(Settings.Scope.USER)),
            bound("Settings", "Open Project Settings File", "settings.openProjectFile", "", () -> openSettingsFile(Settings.Scope.PROJECT)),
            bound("Settings", "Reload Settings", "settings.reload", "", this::reloadSettings));
        return new MenuBar(file, edit, view, languageMenu, run, settingsMenu);
    }

    // ---- customization (v0.8): themes, keybindings, settings ----
    // Everything is stored in plain files (see CUSTOMIZATION.md) and applied only on startup, after the Settings
    // dialog, or when a settings file is saved in the editor or reloaded explicitly.

    /** Light is the default look; other themes add style classes (and, for custom themes, a stylesheet) to the scene root. */
    private void installTheme() {
        // Dialogs open in their own scene, so they do not inherit this window's stylesheet or theme class.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(this::styleDialog);
        });
        refreshThemes();
        applyTheme();
    }

    /** Re-reads the themes folder and picks the configured theme (falling back to the pre-v0.8 saved choice, then light). */
    private void refreshThemes() {
        themes = Themes.available(settings.themesDirectory());
        theme = Themes.find(themes, settings.get(THEME_KEY, preferences.get(THEME_KEY, Themes.DEFAULT_ID)));
        ToggleGroup group = new ToggleGroup();
        themeMenu.getItems().clear();
        for (Theme candidate : themes) {
            RadioMenuItem item = new RadioMenuItem(candidate.name());
            item.setToggleGroup(group);
            item.setSelected(candidate.id().equals(theme.id()));
            item.setOnAction(e -> chooseTheme(candidate));
            themeMenu.getItems().add(item);
        }
    }

    private void chooseTheme(Theme chosen) {
        settings.put(Settings.Scope.USER, THEME_KEY, chosen.id());
        saveUserSettings();
        theme = chosen;
        applyTheme();
        status.setText("Theme: " + chosen.name());
    }

    private void applyTheme() {
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

    /** Gives a dialog or popup the scene's stylesheets, dropping custom-theme sheets that are no longer in use. */
    private void syncStylesheets(Parent target) {
        target.getStylesheets().removeIf(sheet -> themeSheets.contains(sheet) && !scene.getStylesheets().contains(sheet));
        for (String sheet : scene.getStylesheets()) if (!target.getStylesheets().contains(sheet)) target.getStylesheets().add(sheet);
    }

    private void setThemeClass(Parent root) {
        root.getStyleClass().removeAll(Themes.ALL_CLASSES);
        root.getStyleClass().addAll(theme.styleClasses());
    }

    private void applyKeybindings() {
        keyProblems.clear();
        for (Keybindings.Action action : keys.actions()) {
            MenuItem item = menuItems.get(action.id());
            if (item == null) continue;
            String binding = keys.binding(action.id());
            var combination = Shortcuts.parse(binding);
            if (!binding.isEmpty() && combination == null) keyProblems.add("key." + action.id() + ": '" + binding + "' is not a shortcut javelin IDE understands");
            item.setAccelerator(combination);
        }
    }

    /** Re-applies everything derived from settings: editor preferences, theme, shortcuts, and the project tree. */
    private void applySettings() {
        editorPrefs = EditorPreferences.from(settings);
        applyIcon();
        treeExcludes = parseNames(settings.get("tree.exclude", DEFAULT_TREE_EXCLUDES));
        refreshThemes();
        applyTheme();
        applyKeybindings();
        forEachEditor(this::applyEditorPreferences);
        refreshProjectTree();
    }

    private void reloadSettings() {
        settings.reload();
        applySettings();
        if (!reportSettingsProblems(true)) status.setText("Settings reloaded");
    }

    /** Writes problems found in the settings files to the Output panel. Returns true if there were any. */
    private boolean reportSettingsProblems(boolean reveal) {
        List<String> problems = new ArrayList<>(settings.warnings());
        problems.addAll(EditorPreferences.validate(settings));
        problems.addAll(keys.problems());
        problems.addAll(keyProblems);
        for (String problem : problems) output.append("settings: " + problem);
        if (problems.isEmpty()) return false;
        status.setText(problems.size() + " settings problem(s); see Output");
        if (reveal) showBottom(outputTab);
        return true;
    }

    private void saveUserSettings() {
        try { settings.save(Settings.Scope.USER); } catch (IOException ex) { showError("Could not save settings", ex.getMessage()); }
    }

    private void openSettingsDialog() {
        if (SettingsDialog.showUser(scene.getWindow(), settings, keys, themes, theme.id())) { applySettings(); status.setText("Settings saved"); }
    }

    private void openProjectSettingsDialog() {
        if (!requireProject("Project Settings")) return;
        if (SettingsDialog.showProject(scene.getWindow(), settings)) { applySettings(); status.setText("Project settings saved"); }
    }

    /** Opens a settings file in an editor tab, first creating it from a fully commented template if it does not exist. */
    private void openSettingsFile(Settings.Scope scope) {
        Path path = scope == Settings.Scope.USER ? settings.userFile() : settings.projectFile();
        if (path == null) { showError("Project Settings", "Open a project directory first."); return; }
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, scope == Settings.Scope.USER ? SettingsTemplates.user(keys) : SettingsTemplates.project());
                if (scope == Settings.Scope.PROJECT) refreshProjectTree();
            }
        } catch (IOException ex) { showError("Could not create settings file", ex.getMessage()); return; }
        openFile(path.toFile());
    }

    /** True for the settings files and custom theme stylesheets: saving one of them re-applies settings. */
    private boolean isSettingsPath(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        if (path.equals(settings.userFile())) return true;
        if (settings.projectFile() != null && path.equals(settings.projectFile())) return true;
        return path.startsWith(settings.themesDirectory()) && path.toString().toLowerCase(Locale.ROOT).endsWith(".css");
    }

    private void applyEditorPreferences(CodeArea editor) {
        editor.setStyle(editorPrefs.style(zoom));
        editor.setWrapText(editorPrefs.wordWrap());
        editor.setParagraphGraphicFactory(editorPrefs.lineNumbers() ? LineNumberFactory.get(editor) : null);
    }

    private void forEachEditor(Consumer<CodeArea> action) {
        for (Tab tab : tabs.getTabs()) if (tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea a) action.accept(a);
    }

    /** Changes the editors' font size for this session only (0 resets); the saved font size is not touched. */
    private void zoomEditors(int delta) {
        zoom = delta == 0 ? 0 : Math.max(-30, Math.min(30, zoom + delta));
        forEachEditor(this::applyEditorPreferences);
        status.setText(zoom == 0 ? "Font size: " + editorPrefs.fontSize() : "Font size: " + editorPrefs.fontSize() + (zoom > 0 ? " +" : " ") + zoom + " (this session)");
    }

    private void refreshProjectTree() {
        if (projectDirectory == null) return;
        Set<File> expanded = new HashSet<>();
        collectExpanded(projectTree.getRoot(), expanded);
        TreeItem<File> selected = projectTree.getSelectionModel().getSelectedItem();
        File selectedFile = selected == null ? null : selected.getValue();
        projectTree.setRoot(createTreeItem(projectDirectory));
        restoreExpanded(projectTree.getRoot(), expanded);
        projectTree.getRoot().setExpanded(true);
        // A rebuild must not drop the selection, or the next click/Enter/F2 acts on nothing or on another row.
        TreeItem<File> again = selectedFile == null ? null : findTreeItem(projectTree.getRoot(), selectedFile);
        if (again != null) projectTree.getSelectionModel().select(again);
        treeGestures.reset();
        treeWatcher.watch(projectDirectory.toPath()); // what the tree now shows is the baseline, so our own changes are not reported again
    }

    /** Rebuilding the tree must not fold every folder the person had open. */
    private static void collectExpanded(TreeItem<File> item, Set<File> into) {
        if (item == null) return;
        if (item.isExpanded()) into.add(item.getValue());
        for (TreeItem<File> child : item.getChildren()) collectExpanded(child, into);
    }

    private static void restoreExpanded(TreeItem<File> item, Set<File> expanded) {
        if (expanded.contains(item.getValue())) item.setExpanded(true);
        for (TreeItem<File> child : item.getChildren()) restoreExpanded(child, expanded);
    }

    private static TreeItem<File> findTreeItem(TreeItem<File> item, File file) {
        if (item == null) return null;
        if (item.getValue().equals(file)) return item;
        if (!ProjectFiles.isInside(item.getValue().toPath(), file.toPath())) return null;
        for (TreeItem<File> child : item.getChildren()) { TreeItem<File> found = findTreeItem(child, file); if (found != null) return found; }
        return null;
    }

    /** Expands the folders above {@code file}, selects it and scrolls it into view. */
    private void revealInTree(File file) {
        TreeItem<File> item = findTreeItem(projectTree.getRoot(), file);
        if (item == null) return;
        for (TreeItem<File> parent = item.getParent(); parent != null; parent = parent.getParent()) parent.setExpanded(true);
        projectTree.getSelectionModel().select(item);
        projectTree.scrollTo(projectTree.getRow(item));
    }

    /** New file or folder: inside the folder that was right-clicked, beside the file that was, or in the project folder. */
    private void createInTree(boolean folder) {
        String what = folder ? "folder" : "file";
        if (!requireProject("New " + what)) return;
        TreeItem<File> selected = projectTree.getSelectionModel().getSelectedItem();
        Path directory = ProjectFiles.directoryFor((selected == null ? projectDirectory : selected.getValue()).toPath());
        String name = "";
        while (true) {
            TextInputDialog dialog = new TextInputDialog(name);
            dialog.setTitle("New " + what);
            dialog.setHeaderText("New " + what + " in " + (directory.equals(projectDirectory.toPath()) ? projectDirectory.getName() : displayPath(directory.toFile())));
            dialog.setContentText("Name:");
            Optional<String> answer = Dialogs.show(dialog);
            if (answer.isEmpty()) return;
            name = answer.get();
            try {
                Path created = folder ? ProjectFiles.createFolder(directory, name) : ProjectFiles.createFile(directory, name);
                refreshProjectTree();
                revealInTree(created.toFile());
                if (!folder) openFile(created.toFile());
                status.setText("Created " + what + ": " + created);
                return;
            } catch (IOException ex) {
                showError("Could not create " + what, ex.getMessage()); // then ask again, keeping what was typed
            }
        }
    }

    /** Deletes the selected file or folder after asking. This is permanent: it does not go to a trash. */
    private void deleteFromTree() {
        if (!requireProject("Delete")) return;
        TreeItem<File> selected = projectTree.getSelectionModel().getSelectedItem();
        if (selected == null) { status.setText("Select a file or folder to delete first."); return; }
        File target = selected.getValue();
        if (target.equals(projectDirectory)) { status.setText("The project folder itself cannot be deleted from here."); return; }
        Path path = target.toPath();
        List<Tab> open = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) if (tab.getUserData() instanceof File f && ProjectFiles.isInside(path, f.toPath())) open.add(tab);
        long unsaved = open.stream().filter(t -> Boolean.TRUE.equals(dirty.get(t))).count();
        String inside = "";
        if (target.isDirectory()) { try { inside = ProjectFiles.contents(path).describe(); } catch (IOException ignored) { /* the count is only a courtesy */ } }
        StringBuilder message = new StringBuilder(target.isDirectory()
            ? "Delete the folder \"" + target.getName() + "\"" + (inside.isEmpty() ? "?" : " and everything in it (" + inside + ")?")
            : "Delete \"" + target.getName() + "\"?");
        message.append("\n\nThis cannot be undone; the files are not moved to a trash.");
        if (!open.isEmpty()) message.append("\n\n").append(open.size()).append(open.size() == 1 ? " open tab will be closed" : " open tabs will be closed")
            .append(unsaved == 0 ? "." : ", and " + unsaved + (unsaved == 1 ? " has unsaved changes that will be lost." : " have unsaved changes that will be lost."));
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE), cancel = ButtonType.CANCEL;
        confirm.setTitle("Delete"); confirm.setHeaderText(null); confirm.setContentText(message.toString());
        confirm.getButtonTypes().setAll(delete, cancel);
        if (Dialogs.show(confirm).orElse(cancel) != delete) return;
        try {
            ProjectFiles.delete(projectDirectory.toPath(), path);
            status.setText("Deleted: " + displayPath(target));
        } catch (IOException ex) {
            showError("Could not delete", ex.getMessage()); // part of a folder may already be gone; the tree below shows what is left
        }
        for (Tab tab : open) if (!Files.exists(((File) tab.getUserData()).toPath())) tabs.getTabs().remove(tab);
        refreshProjectTree();
    }

    /** The row that was dragged, if the drag carries the path of something inside the project. */
    private File dragSource(Dragboard board) {
        if (projectDirectory == null || !board.hasString()) return null;
        File file = new File(board.getString());
        return ProjectFiles.isInside(projectDirectory.toPath(), file.toPath()) && !file.toPath().toAbsolutePath().normalize().equals(projectDirectory.toPath().toAbsolutePath().normalize()) ? file : null;
    }

    /** The folder a drop on {@code row} would move into, or {@code null} if dropping there makes no sense. */
    private File dropTarget(Dragboard board, File row) {
        File source = dragSource(board);
        if (source == null || row == null) return null;
        File folder = ProjectFiles.directoryFor(row.toPath()).toFile();
        return source.isDirectory() && ProjectFiles.isInside(source.toPath(), folder.toPath()) ? null : folder;
    }

    /** Rename the selected file or folder. Open tabs follow it; nothing is ever overwritten. */
    private void renameInTree() {
        if (!requireProject("Rename")) return;
        TreeItem<File> selected = projectTree.getSelectionModel().getSelectedItem();
        if (selected == null) { status.setText("Select a file or folder to rename first."); return; }
        File target = selected.getValue();
        if (target.equals(projectDirectory)) { status.setText("The project folder itself cannot be renamed from here."); return; }
        String what = target.isDirectory() ? "folder" : "file";
        String name = target.getName();
        while (true) {
            TextInputDialog dialog = new TextInputDialog(name);
            dialog.setTitle("Rename " + what);
            dialog.setHeaderText("Rename " + what + " \"" + target.getName() + "\"");
            dialog.setContentText("New name:");
            Optional<String> answer = Dialogs.show(dialog);
            if (answer.isEmpty()) return;
            name = answer.get();
            try {
                Path renamed = ProjectFiles.rename(projectDirectory.toPath(), target.toPath(), name);
                afterMove(target.toPath(), renamed);
                status.setText(renamed.equals(target.toPath().toAbsolutePath().normalize()) ? "Name unchanged" : "Renamed to: " + displayPath(renamed.toFile()));
                return;
            } catch (IOException ex) {
                showError("Could not rename " + what, ex.getMessage()); // then ask again, keeping what was typed
            }
        }
    }

    /** Moves {@code source} into {@code folder} (drag and drop). Returns whether something moved. */
    private boolean moveInTree(File source, File folder) {
        try {
            Path moved = ProjectFiles.moveInto(projectDirectory.toPath(), source.toPath(), folder.toPath());
            if (moved.equals(source.toPath().toAbsolutePath().normalize())) return false; // dropped where it already is
            afterMove(source.toPath(), moved);
            status.setText("Moved to: " + displayPath(moved.toFile()));
            return true;
        } catch (IOException ex) {
            showError("Could not move", ex.getMessage());
            return false;
        }
    }

    /** After {@code from} became {@code to}: repoint every open tab inside it, tell the language servers, and redraw the tree. */
    private void afterMove(Path from, Path to) {
        for (Tab tab : new ArrayList<>(tabs.getTabs())) {
            if (!(tab.getUserData() instanceof File old)) continue;
            Path now = ProjectFiles.relocated(from, to, old.toPath());
            if (now == null || now.equals(old.toPath().toAbsolutePath().normalize())) continue;
            language.close(old);
            tab.setUserData(now.toFile());
            updateTabTitle(tab);
            if (tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea editor) {
                language.open(now.toFile(), editor.getText());
                highlight(editor); // a new extension can change the language
            }
        }
        refreshProjectTree();
        revealInTree(to.toFile());
    }

    private static Set<String> parseNames(String commaSeparated) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : commaSeparated.split(",")) if (!name.isBlank()) names.add(name.strip());
        return names;
    }

    private Menu createRecentProjectsMenu() {
        Menu recent = new Menu("Recent Projects");
        List<String> projects = recentProjects();
        if (projects.isEmpty()) { MenuItem empty = new MenuItem("No recent projects"); empty.setDisable(true); recent.getItems().add(empty); return recent; }
        for (String path : projects) {
            File directory = new File(path);
            MenuItem item = new MenuItem(directory.getName().isBlank() ? directory.getAbsolutePath() : directory.getName());
            item.setOnAction(e -> openProjectDirectory(directory));
            recent.getItems().add(item);
        }
        return recent;
    }

    private void chooseProjectDirectory(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Open Project Directory");
        File directory = Dialogs.guarded(() -> chooser.showDialog(stage)); if (directory != null) openProjectDirectory(directory);
    }

    private void openProjectDirectory(File directory) {
        if (!directory.isDirectory()) { showError("Could not open project", "The selected directory no longer exists."); return; }
        projectDirectory = directory.getAbsoluteFile();
        settings.setProject(projectDirectory.toPath()); // reads <project>/.javelin-ide/settings.conf if there is one
        applySettings();
        addRecentProject(projectDirectory);
        status.setText("Project: " + projectDirectory.getAbsolutePath());
        reportSettingsProblems(false);
    }

    private TreeItem<File> createTreeItem(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                List<File> sorted = new ArrayList<>(List.of(children));
                sorted.sort(Comparator.comparing(File::isFile).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File child : sorted) if (!treeExcludes.contains(child.getName())) item.getChildren().add(createTreeItem(child));
            }
        }
        return item;
    }

    private void chooseAndOpenFile(Stage stage) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Open File");
        File file = Dialogs.guarded(() -> chooser.showOpenDialog(stage)); if (file != null) openFile(file);
    }

    private void openFile(File file) {
        for (Tab tab : tabs.getTabs()) if (file.equals(tab.getUserData())) { tabs.getSelectionModel().select(tab); return; }
        if (ImageFiles.isImage(file.toPath())) { openImage(file); return; }
        try {
            CodeArea editor = createEditor(); editor.replaceText(FileIO.readText(file.toPath())); editor.moveTo(0);
            Tab tab = createTab(file.getName(), editor, file); tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab);
            dirty.put(tab, false); language.open(file, editor.getText()); highlight(editor);
            status.setText((SyntaxHighlighter.tooLarge(editor.getLength()) ? "Opened (large file, syntax highlighting is off): " : "Opened: ") + file.getAbsolutePath());
        } catch (IOException ex) { showError("Could not open file", ex.getMessage()); }
    }

    /** Pictures open in a read-only viewer tab: no text buffer, no language server, nothing to save. */
    private void openImage(File file) {
        try {
            Tab tab = new Tab(file.getName(), ImageViewerPane.load(file)); tab.setUserData(file);
            tab.setOnCloseRequest(e -> { if (!confirmClose(tab)) e.consume(); });
            tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab);
            status.setText("Opened image (view only): " + file.getAbsolutePath());
        } catch (IOException ex) { showError("Could not open image", ex.getMessage()); }
    }

    private void openFile(File file, int line, int column) {
        openFile(file);
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null || !file.equals(tab.getUserData())) return;
        currentEditor().ifPresent(a -> {
            int paragraph = Math.max(0, Math.min(line - 1, a.getParagraphs().size() - 1));
            a.moveTo(paragraph, Math.min(Math.max(0, column - 1), a.getParagraphLength(paragraph)));
            a.requestFollowCaret(); a.requestFocus();
        });
    }

    private void newDocument() {
        CodeArea editor = createEditor(); Tab tab = createTab("Untitled", editor, null);
        tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab); dirty.put(tab, false); status.setText("New document");
    }

    private CodeArea createEditor() {
        CodeArea editor = new CodeArea();
        applyEditorPreferences(editor);
        editor.setOnKeyPressed(this::handleKeyPressed);
        // Filters, not handlers: they must run before RichTextFX's own key handling, and Tab also arrives as a separate
        // KEY_TYPED event with "\t" that consuming KEY_PRESSED does not stop - it would add a stray tab character.
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::handleTab);
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEnter);
        editor.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (swallowTypedTab && "\t".equals(e.getCharacter())) { swallowTypedTab = false; e.consume(); }
            // Enter is the same story as Tab: without this the typed event adds a second line break after ours.
            if (swallowTypedEnter && Indentation.isLineBreak(e.getCharacter())) { swallowTypedEnter = false; e.consume(); }
        });
        editor.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            if (hasExtraCarets(editor) && !e.getCharacter().isEmpty() && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                List<Integer> positions = extraCarets.get(editor).stream().map(CaretNode::getPosition).sorted(Comparator.reverseOrder()).toList();
                for (int p : positions) editor.insertText(p, e.getCharacter());
                clearExtraCarets(editor); e.consume();
            }
        });
        return editor;
    }

    /**
     * Window, taskbar and Dock icon: the {@code app.icon} image when it can be read, otherwise the built-in one.
     * JavaFX's stage icons cover Windows and Linux; macOS takes its Dock icon from {@code java.awt.Taskbar}.
     */
    private void applyIcon() {
        AppIcon.Result icon = AppIcon.resolve(settings.get(AppIcon.SETTING, ""));
        if (primaryStage != null) {
            List<javafx.scene.image.Image> images = new ArrayList<>();
            for (var image : icon.images()) {
                try { images.add(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(AppIcon.png(image)))); }
                catch (IOException ex) { /* skip this size */ }
            }
            primaryStage.getIcons().setAll(images);
        }
        try {
            if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE))
                java.awt.Taskbar.getTaskbar().setIconImage(icon.images().get(icon.images().size() - 1));
        } catch (Throwable ignored) { /* no Dock/taskbar integration here: the stage icons still apply */ }
        if (icon.warning() != null) status.setText(icon.warning());
    }

    private boolean swallowTypedTab;

    /** Tab inserts spaces/a tab or indents the selected lines; Shift+Tab outdents them (see {@link Indentation}). */
    private void handleTab(KeyEvent e) {
        swallowTypedTab = false;
        if (!(e.getSource() instanceof CodeArea editor) || e.getCode() != KeyCode.TAB) return;
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || hasExtraCarets(editor)) return;
        IndexRange selection = editor.getSelection();
        String text = editor.getText();
        Indentation.Edit edit = e.isShiftDown()
            ? Indentation.outdent(text, selection.getStart(), selection.getEnd(), editorPrefs.tabSize())
            : Indentation.tab(text, selection.getStart(), selection.getEnd(), editorPrefs.tabSize(), editorPrefs.insertSpaces());
        if (!edit.isNoOp()) {
            editor.replaceText(edit.start(), edit.end(), edit.replacement());
            editor.selectRange(edit.selectionStart(), edit.selectionEnd());
        }
        swallowTypedTab = true; e.consume();
    }

    private boolean swallowTypedEnter;

    /** With auto-indent on, Enter inserts one line break plus the right indentation (see {@link Indentation#newline}). */
    private void handleEnter(KeyEvent e) {
        swallowTypedEnter = false;
        if (!(e.getSource() instanceof CodeArea editor) || e.getCode() != KeyCode.ENTER || !editorPrefs.autoIndent()) return;
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || hasExtraCarets(editor)) return;
        IndexRange selection = editor.getSelection();
        Indentation.Edit edit = Indentation.newline(editor.getText(), selection.getStart(), selection.getEnd(), editorPrefs.indentUnit());
        editor.replaceText(edit.start(), edit.end(), edit.replacement());
        editor.selectRange(edit.selectionStart(), edit.selectionEnd());
        editor.requestFollowCaret();
        swallowTypedEnter = true; e.consume();
    }

    private void handleKeyPressed(KeyEvent e) {
        if (!(e.getSource() instanceof CodeArea editor)) return;
        if ((e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE) && hasExtraCarets(editor)) {
            boolean back = e.getCode() == KeyCode.BACK_SPACE;
            List<Integer> positions = extraCarets.get(editor).stream().map(CaretNode::getPosition).sorted(Comparator.reverseOrder()).toList();
            for (int p : positions) {
                if (back && p > 0) editor.deleteText(p - 1, p);
                if (!back && p < editor.getLength()) editor.deleteText(p, p + 1);
            }
            clearExtraCarets(editor); e.consume();
        }
    }

    private Tab createTab(String title, CodeArea editor, File file) {
        Tab tab = new Tab(title, new VirtualizedScrollPane<>(editor)); tab.setUserData(file);
        subscriptions.put(tab, editor.plainTextChanges().subscribe(c -> {
            dirty.put(tab, true); updateTabTitle(tab);
            File current = (File) tab.getUserData();
            if (current != null) language.change(current, c.getPosition(), c.getRemoved().length(), c.getInserted());
            if (current != null) shiftDiagnostics(editor, current, c.getPosition(), c.getRemoved(), c.getInserted());
            highlight(editor);
        }));
        tab.setOnCloseRequest(e -> { if (!confirmClose(tab)) e.consume(); });
        return tab;
    }

    /** Moves the stored language diagnostics along with an edit, so underlines stay on their text until the server republishes. */
    private void shiftDiagnostics(CodeArea editor, File file, int offset, String removed, String inserted) {
        List<Lsp.Diagnostic> reported = diagnostics.get(file);
        if (reported == null) return;
        TwoDimensional.Position at = editor.offsetToPosition(offset, TwoDimensional.Bias.Forward); // text before the edit is unchanged
        List<Lsp.Diagnostic> shifted = DiagnosticShifter.shift(reported, new Lsp.Position(at.getMajor(), at.getMinor()), removed, inserted);
        if (shifted.isEmpty()) diagnostics.remove(file); else diagnostics.put(file, shifted);
    }

    private void updateTabTitle(Tab tab) {
        File f = (File) tab.getUserData(); String title = f == null ? "Untitled" : f.getName();
        tab.setText(Boolean.TRUE.equals(dirty.get(tab)) ? "* " + title : title);
    }

    private Optional<CodeArea> currentEditor() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea a) return Optional.of(a);
        return Optional.empty();
    }

    private void saveCurrent(boolean forceChoose) {
        Tab tab = tabs.getSelectionModel().getSelectedItem(); if (tab == null) return;
        if (tab.getContent() instanceof ImageViewerPane) { status.setText("Images are view only; there is nothing to save."); return; }
        File file = (File) tab.getUserData();
        File previous = file;
        if (file == null || forceChoose) {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Save File");
            file = Dialogs.guarded(() -> chooser.showSaveDialog(tabs.getScene().getWindow())); if (file == null) return;
        }
        CodeArea editor = currentEditor().orElse(null); if (editor == null) return;
        try {
            FileIO.writeAtomic(file.toPath(), editor.getText());
            tab.setUserData(file); dirty.put(tab, false); updateTabTitle(tab); discardSnapshot(tab);
            if (!file.equals(previous)) highlight(editor); // a new name can change whether the file is highlighted
            status.setText("Saved: " + file.getAbsolutePath());
            if (previous != null && !previous.equals(file)) language.close(previous);
            language.saved(file, editor.getText());
            if (projectDirectory != null && file.toPath().startsWith(projectDirectory.toPath())) refreshProjectTree();
            if (isSettingsPath(file)) reloadSettings();
        } catch (IOException ex) { showError("Could not save file", ex.getMessage()); }
    }

    private void closeCurrentTab() { Tab tab = tabs.getSelectionModel().getSelectedItem(); if (tab != null && confirmClose(tab)) tabs.getTabs().remove(tab); }

    private boolean confirmClose(Tab tab) {
        if (!Boolean.TRUE.equals(dirty.get(tab))) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes"); alert.setHeaderText("Save changes to " + tab.getText().replaceFirst("^\\* ", "") + "?");
        ButtonType save = new ButtonType("Save"), discard = new ButtonType("Discard"), cancel = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(save, discard, cancel);
        ButtonType result = Dialogs.show(alert).orElse(cancel);
        if (result == save) { tabs.getSelectionModel().select(tab); saveCurrent(false); return !Boolean.TRUE.equals(dirty.get(tab)); }
        if (result == discard) { discardSnapshot(tab); return true; }
        return false;
    }

    private boolean confirmCloseDirtyTabs() { for (Tab tab : new ArrayList<>(tabs.getTabs())) if (!confirmClose(tab)) return false; return true; }

    /** The text of the last Search, so Find Next / Find Previous (F3 / Shift+F3) can repeat it. */
    private String lastSearch = "";
    private static final boolean SEARCH_IGNORE_CASE = true;

    private void search() {
        currentEditor().ifPresent(a -> {
            String selected = a.getSelectedText();
            String initial = !selected.isEmpty() && selected.indexOf('\n') < 0 ? selected : lastSearch;
            TextInputDialog d = new TextInputDialog(initial); d.setTitle("Search"); d.setHeaderText("Find text (F3: next, Shift+F3: previous)"); d.setContentText("Search:");
            Dialogs.show(d).filter(s -> !s.isEmpty()).ifPresent(q -> {
                lastSearch = q;
                showMatch(a, q, TextSearch.first(a.getText(), q, a.getCaretPosition(), SEARCH_IGNORE_CASE), SEARCH_IGNORE_CASE);
            });
        });
    }

    private void findNext() {
        currentEditor().ifPresent(a -> {
            if (lastSearch.isEmpty()) { search(); return; }
            IndexRange sel = a.getSelection();
            showMatch(a, lastSearch, TextSearch.next(a.getText(), lastSearch, sel.getStart(), sel.getEnd(), SEARCH_IGNORE_CASE), SEARCH_IGNORE_CASE);
        });
    }

    private void findPrevious() {
        currentEditor().ifPresent(a -> {
            if (lastSearch.isEmpty()) { search(); return; }
            showMatch(a, lastSearch, TextSearch.previous(a.getText(), lastSearch, a.getSelection().getStart(), SEARCH_IGNORE_CASE), SEARCH_IGNORE_CASE);
        });
    }

    /** Selects the match and scrolls it into view, then gives the editor focus back (the Search dialog had it). */
    private void showMatch(CodeArea a, String query, TextSearch.Match match, boolean ignoreCase) {
        if (match == null) { status.setText("Not found: " + query); return; }
        a.selectRange(match.start(), match.end());
        a.requestFollowCaret(); a.requestFocus();
        Platform.runLater(() -> { a.requestFollowCaret(); a.requestFocus(); });   // once the dialog has finished closing
        status.setText("Found " + TextSearch.ordinal(a.getText(), query, match.start(), ignoreCase) + " of " + TextSearch.count(a.getText(), query, ignoreCase) + ": " + query);
    }

    private void replace() {
        currentEditor().ifPresent(a -> {
            Dialog<String> f = new Dialog<>(); f.setTitle("Replace"); f.setHeaderText("Find text");
            TextField findField = new TextField();
            CheckBox ignoreCase = new CheckBox("Ignore case");
            f.getDialogPane().setContent(new VBox(8, new Label("Find:"), findField, ignoreCase));
            f.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            f.setResultConverter(button -> button == ButtonType.OK ? findField.getText() : null);
            Optional<String> q = Dialogs.show(f); if (q.isEmpty() || q.get().isEmpty()) return;
            TextInputDialog r = new TextInputDialog(); r.setTitle("Replace"); r.setHeaderText("Replacement text"); r.setContentText("Replace with:");
            Dialogs.show(r).ifPresent(v -> {
                String original = a.getText();
                String replaced = TextSearch.replace(original, q.get(), v, ignoreCase.isSelected());
                if (!original.equals(replaced)) a.replaceText(0, a.getLength(), replaced);
                status.setText("Replaced: " + q.get());
            });
        });
    }

    private void goToLine() {
        currentEditor().ifPresent(a -> {
            TextInputDialog d = new TextInputDialog(String.valueOf(a.getCurrentParagraph() + 1));
            d.setTitle("Go to Line"); d.setHeaderText("Line number"); d.setContentText("Line:");
            Dialogs.show(d).ifPresent(s -> {
                try { int n = Integer.parseInt(s.trim()); if (n < 1 || n > a.getParagraphs().size()) throw new NumberFormatException();
                    a.moveTo(n - 1, 0); a.requestFollowCaret(); status.setText("Line " + n);
                } catch (NumberFormatException ex) { showError("Invalid line", "Enter a valid line number."); }
            });
        });
    }

    private void goToFile() {
        if (projectDirectory == null) { showError("Go to File", "Open a project directory first."); return; }
        TextInputDialog d = new TextInputDialog(); d.setTitle("Go to File"); d.setHeaderText("Project file name"); d.setContentText("File:");
        Dialogs.show(d).filter(s -> !s.isBlank()).ifPresent(q -> { File f = findFile(projectDirectory, q.trim()); if (f != null) openFile(f); else status.setText("File not found: " + q); });
    }

    private File findFile(File dir, String q) {
        File[] files = dir.listFiles(); if (files == null) return null;
        for (File f : files) if (f.isFile() && f.getName().equals(q)) return f;
        for (File f : files) if (f.isDirectory() && !treeExcludes.contains(f.getName())) { File hit = findFile(f, q); if (hit != null) return hit; }
        return null;
    }

    private void matchBracket() {
        currentEditor().ifPresent(a -> {
            String s = a.getText(); int p = a.getCaretPosition();
            int b = p < s.length() && "([{)]}".indexOf(s.charAt(p)) >= 0 ? p : (p > 0 && "([{)]}".indexOf(s.charAt(p - 1)) >= 0 ? p - 1 : -1);
            if (b < 0) { status.setText("No bracket at caret"); return; }
            char c = s.charAt(b), open = ")]}".indexOf(c) >= 0 ? "([{".charAt(")]}".indexOf(c)) : c;
            char close = "([{".indexOf(c) >= 0 ? ")]}".charAt("([{".indexOf(c)) : c;
            int dir = "([{".indexOf(c) >= 0 ? 1 : -1, depth = 0;
            for (int i = b; i >= 0 && i < s.length(); i += dir) {
                char x = s.charAt(i);
                if (x == open) depth += dir;
                else if (x == close) depth -= dir;
                if (depth == 0) { a.selectRange(Math.min(b, i), Math.max(b, i) + 1); status.setText("Matching bracket"); return; }
            }
            status.setText("No matching bracket");
        });
    }

    private void addCursor() {
        currentEditor().ifPresent(a -> {
            CaretNode c = new CaretNode("extra-" + System.nanoTime(), a, a.getCaretPosition());
            a.addCaret(c); extraCarets.computeIfAbsent(a, k -> new ArrayList<>()).add(c); status.setText("Additional cursor added");
        });
    }

    private boolean hasExtraCarets(CodeArea a) { return extraCarets.containsKey(a) && !extraCarets.get(a).isEmpty(); }

    private void clearExtraCarets(CodeArea a) {
        List<CaretNode> list = extraCarets.remove(a); if (list != null) list.forEach(a::removeCaret);
    }

    private void highlight(CodeArea a) {
        String text = a.getText();
        if (text.isEmpty()) return; // StyleSpansBuilder refuses to build zero spans
        if (SyntaxHighlighter.tooLarge(text.length())) { a.clearStyle(0, text.length()); return; } // keeps typing responsive in huge files
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>(); int last = 0;
        for (SyntaxHighlighter.Span span : SyntaxHighlighter.spans(text, fileOf(a))) {
            if (span.start() > last) b.add(Collections.emptyList(), span.start() - last);
            b.add(Collections.singleton(span.style()), span.end() - span.start()); last = span.end();
        }
        if (last < text.length()) b.add(Collections.emptyList(), text.length() - last);
        StyleSpans<Collection<String>> spans = b.create();
        List<Lsp.Diagnostic> reported = diagnostics.get(fileOf(a));
        if (reported != null && !reported.isEmpty() && text.length() > 0) {
            spans = spans.overlay(diagnosticSpans(a, reported, text.length()), (base, extra) -> { List<String> merged = new ArrayList<>(base); merged.addAll(extra); return merged; });
        }
        a.setStyleSpans(0, spans);
    }

    /** Underline styles for the given diagnostics, one span per non-overlapping range. */
    private StyleSpans<Collection<String>> diagnosticSpans(CodeArea a, List<Lsp.Diagnostic> reported, int length) {
        List<int[]> ranges = new ArrayList<>(); // {start, end, severity}
        for (Lsp.Diagnostic d : reported) {
            int start = offsetOf(a, d.range().start()), end = offsetOf(a, d.range().end());
            if (end <= start) { if (start < length) end = start + 1; else if (start > 0) { end = start; start--; } else continue; }
            ranges.add(new int[] {start, end, d.severity()});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int at = 0;
        for (int[] r : ranges) {
            int start = Math.max(r[0], at), end = Math.min(r[1], length);
            if (end <= start) continue;
            if (start > at) b.add(Collections.emptyList(), start - at);
            b.add(Collections.singleton(r[2] == Lsp.Diagnostic.ERROR ? "diag-error" : "diag-warning"), end - start);
            at = end;
        }
        if (at < length) b.add(Collections.emptyList(), length - at);
        return b.create();
    }

    private void showBottom(Tab tab) {
        if (!editorSplit.getItems().contains(bottomTabs)) { editorSplit.getItems().add(bottomTabs); editorSplit.setDividerPositions(0.7); }
        bottomTabs.getSelectionModel().select(tab);
    }

    private void toggleBottomPanel() {
        if (editorSplit.getItems().contains(bottomTabs)) editorSplit.getItems().remove(bottomTabs);
        else showBottom(bottomTabs.getSelectionModel().getSelectedItem());
    }

    private void build() {
        if (!requireProject("Build")) return;
        String customBuild = settings.get("build.command", "").trim();
        if (!customBuild.isEmpty()) { problems.clear(); output.append("Using build.command from settings"); runCommand("Build", customBuild); return; }
        Optional<BuildSystem> system = BuildSystem.detect(projectDirectory);
        if (system.isEmpty()) { showError("Build", "No supported build file found (pom.xml, build.gradle, Makefile, Cargo.toml, package.json). Use Run > Run Command... instead."); return; }
        problems.clear();
        runCommand(system.get().name() + " build", system.get().buildCommand());
    }

    private void runCommandDialog() {
        if (!requireProject("Run Command")) return;
        String key = "run.command." + Integer.toHexString(projectDirectory.getAbsolutePath().hashCode());
        String configured = settings.get("run.command", "").trim(); // a run.command in settings is the project's own default
        String suggestion = !configured.isEmpty() ? configured : preferences.get(key, BuildSystem.detect(projectDirectory).map(BuildSystem::suggestedRunCommand).orElse(""));
        TextInputDialog d = new TextInputDialog(suggestion);
        d.setTitle("Run Command"); d.setHeaderText("Command to run in " + projectDirectory.getAbsolutePath()); d.setContentText("Command:");
        Dialogs.show(d).filter(s -> !s.isBlank()).ifPresent(command -> { preferences.put(key, command.trim()); runCommand("Run", command.trim()); });
    }

    private boolean requireProject(String title) {
        if (projectDirectory != null) return true;
        showError(title, "Open a project directory first.");
        return false;
    }

    private void runCommand(String name, String commandLine) {
        long unsaved = tabs.getTabs().stream().filter(t -> Boolean.TRUE.equals(dirty.get(t))).count();
        showBottom(outputTab);
        output.append("$ " + commandLine + "    (in " + projectDirectory.getAbsolutePath() + ")");
        if (unsaved > 0) output.append("Note: " + unsaved + " unsaved file(s) are not included; the command uses files on disk.");
        ProblemParser parser = new ProblemParser(); // stateful (multi-line diagnostics); only touched on the FX thread
        try {
            processes.start(name, ShellCommand.wrap(commandLine), projectDirectory, new ProcessManager.Listener() {
                @Override public void onLine(ManagedProcess p, String line) {
                    Platform.runLater(() -> { output.append(line); parser.accept(line).ifPresent(problems::add); });
                }
                @Override public void onExit(ManagedProcess p, int code) {
                    Platform.runLater(() -> { output.append("[" + name + " exited with code " + code + "]"); status.setText(name + " exited with code " + code); });
                }
            });
            status.setText("Running: " + commandLine);
        } catch (IOException ex) { showError("Could not start command", ex.getMessage()); }
    }

    private void openProblem(Problem problem) {
        File file = new File(problem.file());
        if (!file.isAbsolute() && projectDirectory != null) file = new File(projectDirectory, problem.file());
        if (file.isFile()) openFile(file, problem.line(), problem.column()); else status.setText("File not found: " + problem.file());
    }

    private void stopAllProcesses() {
        int count = processes.running().size();
        processes.stopAll();
        status.setText(count == 0 ? "No running processes" : "Stopping " + count + " process(es)");
    }

    private void showProcesses() {
        List<ManagedProcess> running = processes.running();
        if (running.isEmpty()) { status.setText("No running processes"); return; }
        ChoiceDialog<ManagedProcess> d = new ChoiceDialog<>(running.get(running.size() - 1), running);
        d.setTitle("Processes"); d.setHeaderText("Select a process to stop"); d.setContentText("Process:");
        Dialogs.show(d).ifPresent(processes::stop);
    }

    // ---- language intelligence (v0.5) ----
    // Servers are started only from Language > Start Language Server..., with a command the user confirms.
    // Results are shown near the caret or in the status bar, never as unsolicited dialogs.

    private void onLanguageLog(String line) { Platform.runLater(() -> output.append(line)); }

    private void updateLanguageStatus() {
        List<String> running = language.runningLanguages();
        languageStatus.setText(running.isEmpty() ? "" : "Language servers: " + String.join(", ", running));
    }

    private void onDiagnostics(URI uri, List<Lsp.Diagnostic> reported) {
        Platform.runLater(() -> {
            File file = toFile(uri);
            if (file == null) return;
            if (reported.isEmpty()) diagnostics.remove(file); else diagnostics.put(file, reported);
            List<Problem> list = new ArrayList<>();
            for (Lsp.Diagnostic d : reported) {
                Problem.Severity severity = d.severity() == Lsp.Diagnostic.ERROR ? Problem.Severity.ERROR : Problem.Severity.WARNING;
                String text = d.source() == null ? d.message() : d.message() + "  [" + d.source() + "]";
                list.add(new Problem(severity, displayPath(file), d.range().start().line() + 1, d.range().start().character() + 1, text.replace('\n', ' ')));
            }
            problems.replace("language:" + file.getPath(), list);
            CodeArea editor = editorFor(file);
            if (editor != null) highlight(editor);
        });
    }

    private String displayPath(File file) {
        if (projectDirectory != null && file.toPath().startsWith(projectDirectory.toPath())) return projectDirectory.toPath().relativize(file.toPath()).toString();
        return file.getPath();
    }

    private void forgetTab(Tab tab) {
        Subscription subscription = subscriptions.remove(tab);
        if (subscription != null) subscription.unsubscribe();
        dirty.remove(tab);
        discardSnapshot(tab);
        File file = (File) tab.getUserData();
        if (file != null) language.close(file);
    }

    // ---- crash recovery: unsaved buffers are copied to the user's own disk every few seconds and removed once saved or discarded ----

    private void startCrashRecovery() {
        // Other javelin IDE instances (one per project) share the recovery folder: claim our own snapshots first, so
        // their unsaved work is never offered to this window and ours is never offered to them.
        try { recovery.startSession(); } catch (IOException e) { status.setText("Crash recovery is not shared safely with other windows: " + e.getMessage()); }
        recoveryWriter.execute(() -> recovery.purgeOlderThan(java.time.Duration.ofDays(30)));
        autosave = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(AUTOSAVE_SECONDS), e -> snapshotDirtyTabs()));
        autosave.setCycleCount(javafx.animation.Animation.INDEFINITE);
        autosave.play();
        List<RecoveryStore.Snapshot> leftovers = recovery.pending();
        if (leftovers.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Recover unsaved work");
        alert.setHeaderText(leftovers.size() + (leftovers.size() == 1 ? " document was" : " documents were") + " not saved when javelin IDE last closed unexpectedly.");
        alert.setContentText("Restore " + (leftovers.size() == 1 ? "it" : "them") + " into editor tabs? Choosing Discard deletes the recovered copies.");
        ButtonType restore = new ButtonType("Restore"), discard = new ButtonType("Discard");
        alert.getButtonTypes().setAll(restore, discard);
        if (Dialogs.show(alert).orElse(discard) != restore) { recovery.clear(); return; }
        for (RecoveryStore.Snapshot snapshot : leftovers.stream().sorted(RecoveryStore.TAB_ORDER).toList()) restoreSnapshot(snapshot); // tabs come back in their old order
        status.setText("Restored " + leftovers.size() + (leftovers.size() == 1 ? " unsaved document" : " unsaved documents"));
    }

    private void restoreSnapshot(RecoveryStore.Snapshot snapshot) {
        File original = snapshot.original() == null ? null : snapshot.original().toFile();
        CodeArea editor = createEditor(); editor.replaceText(snapshot.text()); editor.moveTo(snapshot.caret());
        Tab tab = createTab(original == null ? "Untitled" : original.getName(), editor, original);
        tabs.getTabs().add(tab);
        dirty.put(tab, true); updateTabTitle(tab);
        recoveryIds.put(tab, snapshot.id()); // keep the same snapshot file, so it is replaced rather than duplicated
        if (original != null && original.isFile() && original.lastModified() > snapshot.savedAt().toEpochMilli())
            status.setText(original.getName() + " changed on disk after this copy was made; check before saving over it.");
        tabs.getSelectionModel().select(tab);
        highlight(editor);
        editor.requestFollowCaret(); // scroll to where the cursor was
    }

    private void snapshotDirtyTabs() {
        for (Tab tab : tabs.getTabs()) {
            if (!Boolean.TRUE.equals(dirty.get(tab))) continue;
            if (!(tab.getContent() instanceof VirtualizedScrollPane<?> pane) || !(pane.getContent() instanceof CodeArea editor)) continue;
            String id = recoveryIds.computeIfAbsent(tab, t -> recovery.newSessionId());
            File file = (File) tab.getUserData();
            String text = editor.getText();
            int caret = editor.getCaretPosition(), tabIndex = tabs.getTabs().indexOf(tab);
            recoveryWriter.execute(() -> { try { recovery.saveIfChanged(id, file == null ? null : file.toPath(), text, caret, tabIndex); } catch (IOException ignored) { /* retried on the next tick */ } });
        }
    }

    private void discardSnapshot(Tab tab) {
        String id = recoveryIds.remove(tab);
        if (id != null) recoveryWriter.execute(() -> recovery.discard(id));
    }

    private static File toFile(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
        try { return new File(uri); } catch (IllegalArgumentException ex) { return null; }
    }

    private File fileOf(CodeArea editor) {
        for (Tab tab : tabs.getTabs()) if (tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() == editor) return (File) tab.getUserData();
        return null;
    }

    private CodeArea editorFor(File file) {
        for (Tab tab : tabs.getTabs())
            if (file.equals(tab.getUserData()) && tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea a) return a;
        return null;
    }

    private int offsetOf(CodeArea a, Lsp.Position position) {
        int line = Math.max(0, Math.min(position.line(), a.getParagraphs().size() - 1));
        int column = Math.max(0, Math.min(position.character(), a.getParagraphLength(line)));
        return a.getAbsolutePosition(line, column);
    }

    /** The caret, nudged left when it sits just after an identifier so that the identifier is the symbol under it. */
    private int symbolOffset(CodeArea a) {
        int caret = a.getCaretPosition();
        String text = a.getText();
        boolean onWord = caret < text.length() && Character.isJavaIdentifierPart(text.charAt(caret));
        if (!onWord && caret > 0 && Character.isJavaIdentifierPart(text.charAt(caret - 1))) return caret - 1;
        return caret;
    }

    private String wordAt(CodeArea a, int offset) {
        String text = a.getText();
        if (offset < 0 || offset >= text.length() || !Character.isJavaIdentifierPart(text.charAt(offset))) return "";
        int start = offset, end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private void popupStyler(Parent root) {
        syncStylesheets(root);
        setThemeClass(root);
    }

    /** Runs a language action against the current editor, which must hold a saved file with a recognized extension. */
    private void withLanguageEditor(java.util.function.BiConsumer<CodeArea, File> action) {
        CodeArea editor = currentEditor().orElse(null);
        if (editor == null) { status.setText("Open a file first"); return; }
        File file = fileOf(editor);
        if (file == null) { status.setText("Save the file first; language features need a file on disk"); return; }
        if (Languages.idFor(file) == null) { status.setText("No language support for this file type"); return; }
        action.accept(editor, file);
    }

    private void languageError(Throwable error) {
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        status.setText(t.getMessage() == null ? "Language server error" : t.getMessage());
    }

    private void startLanguageServer() {
        withLanguageEditor((editor, file) -> {
            String id = Languages.idFor(file);
            if (language.isRunning(id)) { status.setText("The " + id + " language server is already running"); return; }
            File root = projectDirectory != null ? projectDirectory : file.getAbsoluteFile().getParentFile();
            String key = "language.server." + id;
            TextInputDialog d = new TextInputDialog(settings.get(key, preferences.get(key, Languages.suggestedCommand(id))));
            d.setTitle("Start Language Server");
            d.setHeaderText("Command for the " + id + " language server.\nIt runs in " + root.getAbsolutePath() + " and is started only if you confirm.");
            d.setContentText("Command:");
            Dialogs.show(d).filter(s -> !s.isBlank()).ifPresent(command -> {
                preferences.put(key, command.trim());
                try {
                    status.setText("Starting " + id + " language server...");
                    language.start(id, command.trim(), root).whenComplete((v, err) -> Platform.runLater(() -> {
                        if (err == null) status.setText("Language server ready: " + id);
                        else { languageError(err); status.setText("Language server failed to start: " + status.getText()); showBottom(outputTab); }
                    }));
                } catch (IOException ex) { showError("Could not start language server", ex.getMessage()); }
            });
        });
    }

    private void stopLanguageServer() {
        List<String> running = language.runningLanguages();
        if (running.isEmpty()) { status.setText("No language servers running"); return; }
        String current = currentEditor().map(this::fileOf).map(Languages::idFor).orElse(null);
        String target = running.contains(current) ? current : running.size() == 1 ? running.get(0) : null;
        if (target == null) {
            ChoiceDialog<String> d = new ChoiceDialog<>(running.get(0), running);
            d.setTitle("Stop Language Server"); d.setHeaderText("Select a language server to stop"); d.setContentText("Language:");
            target = Dialogs.show(d).orElse(null);
        }
        if (target != null) { language.stop(target); status.setText("Stopping " + target + " language server"); }
    }

    private void complete() {
        withLanguageEditor((editor, file) -> {
            int requestCaret = editor.getCaretPosition();
            language.completion(file, requestCaret).whenComplete((items, err) -> Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (items.isEmpty()) { status.setText("No completions"); return; }
                if (editor.getCaretPosition() != requestCaret) return; // the user moved on
                showCompletions(editor, items, requestCaret);
            }));
        });
    }

    private record Candidate(Lsp.CompletionItem item, int start, int end) {}

    private void showCompletions(CodeArea editor, List<Lsp.CompletionItem> items, int requestCaret) {
        String text = editor.getText();
        int wordStart = requestCaret;
        while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) wordStart--;
        List<CompletionPopup.Entry> entries = new ArrayList<>();
        int popupStart = wordStart;
        for (Lsp.CompletionItem item : items.stream().limit(500).toList()) {
            int start = item.edit() != null ? offsetOf(editor, item.edit().range().start()) : wordStart;
            int end = item.edit() != null ? offsetOf(editor, item.edit().range().end()) : requestCaret;
            if (item.edit() != null) popupStart = start;
            entries.add(new CompletionPopup.Entry(item.label(), item.detail(), item.filter(), new Candidate(item, start, end)));
        }
        completionPopup.show(editor, entries, popupStart, entry -> {
            Candidate c = (Candidate) entry.payload();
            int shift = editor.getCaretPosition() - requestCaret; // characters typed (or deleted) while the list was open
            int end = Math.max(c.start(), Math.min(c.end() + shift, editor.getLength()));
            editor.replaceText(Math.min(c.start(), editor.getLength()), end, c.item().text());
        }, this::popupStyler);
    }

    private void hover() {
        withLanguageEditor((editor, file) -> language.hover(file, symbolOffset(editor)).whenComplete((text, err) -> Platform.runLater(() -> {
            if (err != null) { languageError(err); return; }
            if (text.isBlank()) { status.setText("No hover information"); return; }
            hoverPopup.show(editor, text, this::popupStyler);
        })));
    }

    private void signatureHelp() {
        withLanguageEditor((editor, file) -> {
            int requestCaret = editor.getCaretPosition();
            language.signatureHelp(file, requestCaret).whenComplete((text, err) -> Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (text.isBlank()) { status.setText("No signature help here"); return; }
                hoverPopup.show(editor, text, this::popupStyler);
            }));
        });
    }

    /** Lists the file's outline in the References panel; double-click jumps to a symbol. Read-only. */
    private void documentSymbols() {
        withLanguageEditor((editor, file) -> language.documentSymbols(file).whenComplete((symbols, err) -> {
            Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                List<ReferencesPanel.Reference> entries = new ArrayList<>();
                for (Lsp.Symbol symbol : symbols) {
                    int line = Math.max(0, Math.min(symbol.range().start().line(), editor.getParagraphs().size() - 1));
                    String label = "  ".repeat(symbol.depth()) + symbol.kindName() + " " + symbol.name() + (symbol.detail() == null || symbol.detail().isBlank() ? "" : "  " + symbol.detail());
                    entries.add(new ReferencesPanel.Reference(file, line + 1, symbol.range().start().character() + 1, label));
                }
                references.show("Symbols in " + file.getName() + " (" + entries.size() + ")", entries);
                showBottom(referencesTab);
                status.setText(entries.isEmpty() ? "No symbols found" : entries.size() + " symbol(s)");
            });
        }));
    }

    /**
     * Asks the server for formatting edits and applies them to the editor. Nothing is written to disk, and the
     * result is one undoable step per edit. If the text changed while the server was working, the result is discarded.
     */
    private void formatDocument() {
        withLanguageEditor((editor, file) -> {
            String snapshot = editor.getText();
            language.formatting(file, editorPrefs.tabSize(), editorPrefs.insertSpaces()).whenComplete((edits, err) -> Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (edits.isEmpty()) { status.setText("Nothing to format"); return; }
                if (!snapshot.equals(editor.getText())) { status.setText("The file changed while formatting; nothing was applied"); return; }
                List<int[]> spans = new ArrayList<>(); // {start, end, edit index}
                for (int i = 0; i < edits.size(); i++) spans.add(new int[] {offsetOf(editor, edits.get(i).range().start()), offsetOf(editor, edits.get(i).range().end()), i});
                spans.sort((a, b) -> Integer.compare(b[0], a[0])); // bottom to top so earlier offsets stay valid
                for (int[] span : spans) replaceMinimal(editor, span[0], span[1], edits.get(span[2]).newText());
                status.setText("Formatted " + file.getName() + " (" + edits.size() + " edit(s), unsaved)");
            }));
        });
    }

    private void goToDefinition() {
        withLanguageEditor((editor, file) -> language.definition(file, symbolOffset(editor)).whenComplete((locations, err) -> {
            List<ReferencesPanel.Reference> found = err == null ? toReferences(locations) : List.of();
            Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (found.isEmpty()) { status.setText(locations.isEmpty() ? "No definition found" : "The definition is not in a local file"); return; }
                if (found.size() == 1) { openFile(found.get(0).file(), found.get(0).line(), found.get(0).column()); return; }
                references.show("Definitions (" + found.size() + ")", found); showBottom(referencesTab);
            });
        }));
    }

    private void findReferences() {
        withLanguageEditor((editor, file) -> {
            String word = wordAt(editor, symbolOffset(editor));
            language.references(file, symbolOffset(editor)).whenComplete((locations, err) -> {
                List<ReferencesPanel.Reference> found = err == null ? toReferences(locations) : List.of();
                Platform.runLater(() -> {
                    if (err != null) { languageError(err); return; }
                    references.show("References" + (word.isEmpty() ? "" : " to " + word) + " (" + found.size() + ")", found);
                    showBottom(referencesTab);
                    status.setText(found.isEmpty() ? "No references found" : found.size() + " reference(s)");
                });
            });
        });
    }

    /** Converts server locations to panel entries, reading each file once for the line previews. Runs off the FX thread. */
    private List<ReferencesPanel.Reference> toReferences(List<Lsp.Location> locations) {
        Map<File, List<String>> lines = new HashMap<>();
        List<ReferencesPanel.Reference> out = new ArrayList<>();
        for (Lsp.Location location : locations) {
            File file = toFile(location.uri());
            if (file == null) continue;
            List<String> content = lines.computeIfAbsent(file, f -> {
                try { return Files.readAllLines(f.toPath()); } catch (IOException | RuntimeException ex) { return List.of(); }
            });
            int line = location.range().start().line();
            out.add(new ReferencesPanel.Reference(file, line + 1, location.range().start().character() + 1, line < content.size() ? content.get(line) : ""));
        }
        return out;
    }

    private void renameSymbol() {
        withLanguageEditor((editor, file) -> {
            int offset = symbolOffset(editor);
            String word = wordAt(editor, offset);
            if (word.isEmpty()) { status.setText("Put the caret on the symbol to rename"); return; }
            TextInputDialog d = new TextInputDialog(word);
            d.setTitle("Rename Symbol"); d.setHeaderText("Rename " + word + "\nYou will see what changes before anything is applied."); d.setContentText("New name:");
            Dialogs.show(d).map(String::trim).filter(n -> !n.isEmpty() && !n.equals(word)).ifPresent(newName ->
                language.rename(file, offset, newName).whenComplete((edit, err) -> Platform.runLater(() -> {
                    if (err != null) { languageError(err); return; }
                    applyRename(word, newName, edit);
                })));
        });
    }

    /** Shows the server's proposed edits and, on approval, applies them to editor tabs. Nothing is written to disk. */
    private void applyRename(String oldName, String newName, Lsp.WorkspaceEdit edit) {
        if (edit.unsupported()) { showError("Rename", "The language server also asked to create, rename or delete files, which javelin IDE never does automatically. Nothing was changed."); return; }
        Map<File, List<Lsp.TextEdit>> byFile = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<URI, List<Lsp.TextEdit>> e : edit.changes().entrySet()) {
            File file = toFile(e.getKey());
            if (file == null) skipped += e.getValue().size(); else byFile.put(file, e.getValue());
        }
        int total = byFile.values().stream().mapToInt(List::size).sum();
        if (total == 0) { status.setText("The server proposed no changes"); return; }
        StringBuilder summary = new StringBuilder("Rename " + oldName + " to " + newName + ": " + total + " edit(s) in " + byFile.size() + " file(s).\n\n");
        int shown = 0;
        for (Map.Entry<File, List<Lsp.TextEdit>> e : byFile.entrySet()) {
            if (shown++ == 15) { summary.append("  ...\n"); break; }
            summary.append("  ").append(displayPath(e.getKey())).append("  (").append(e.getValue().size()).append(")\n");
        }
        if (skipped > 0) summary.append("\n").append(skipped).append(" edit(s) in non-file locations will be skipped.\n");
        summary.append("\nFiles that are not open will be opened in tabs. The changes stay unsaved, so you can review and undo them.");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        ButtonType apply = new ButtonType("Apply"), cancel = ButtonType.CANCEL;
        confirm.setTitle("Rename Symbol"); confirm.setHeaderText("Apply rename?"); confirm.setContentText(summary.toString());
        confirm.getButtonTypes().setAll(apply, cancel);
        if (Dialogs.show(confirm).orElse(cancel) != apply) { status.setText("Rename cancelled"); return; }
        for (Map.Entry<File, List<Lsp.TextEdit>> e : byFile.entrySet()) {
            openFile(e.getKey());
            CodeArea editor = editorFor(e.getKey());
            if (editor == null) continue;
            List<int[]> spans = new ArrayList<>(); // {start, end, edit index}
            List<Lsp.TextEdit> edits = e.getValue();
            for (int i = 0; i < edits.size(); i++) spans.add(new int[] {offsetOf(editor, edits.get(i).range().start()), offsetOf(editor, edits.get(i).range().end()), i});
            spans.sort((a, b) -> Integer.compare(b[0], a[0])); // bottom to top so earlier offsets stay valid
            for (int[] span : spans) replaceMinimal(editor, span[0], span[1], edits.get(span[2]).newText());
        }
        status.setText("Renamed " + oldName + " to " + newName + " (" + total + " edit(s), unsaved)");
    }
 
    /** Replaces a range but leaves untouched the leading and trailing text that is identical, so whole-file edits stay small. */
    private void replaceMinimal(CodeArea editor, int start, int end, String replacement) {
        String old = editor.getText(start, end);
        int prefix = 0, max = Math.min(old.length(), replacement.length());
        while (prefix < max && old.charAt(prefix) == replacement.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix && old.charAt(old.length() - 1 - suffix) == replacement.charAt(replacement.length() - 1 - suffix)) suffix++;
        if (prefix == old.length() && prefix == replacement.length()) return;
        editor.replaceText(start + prefix, end - suffix, replacement.substring(prefix, replacement.length() - suffix));
    }

    private List<String> recentProjects() {
        List<String> projects = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT_PROJECTS; i++) { String path = preferences.get("recent.project." + i, ""); if (!path.isBlank() && new File(path).isDirectory()) projects.add(path); }
        return projects;
    }

    private void addRecentProject(File directory) {
        List<String> projects = recentProjects(); String path = directory.getAbsolutePath(); projects.remove(path); projects.add(0, path);
        for (int i = 0; i < MAX_RECENT_PROJECTS; i++) { if (i < projects.size()) preferences.put("recent.project." + i, projects.get(i)); else preferences.remove("recent.project." + i); }
    }

    private void showError(String title, String message) { Alert a = new Alert(Alert.AlertType.ERROR); a.setTitle(title); a.setHeaderText(null); a.setContentText(message == null ? "Unknown error." : message); Dialogs.show(a); }

    @Override public void stop() { treeWatcher.close(); if (autosave != null) autosave.stop(); recoveryWriter.shutdown(); language.stopAll(); processes.stopAll(); subscriptions.values().forEach(Subscription::unsubscribe); }

    public static void main(String[] args) { launch(args); }
}
