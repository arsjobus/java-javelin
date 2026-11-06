package com.javelin.ui.menu;

import com.javelin.settings.DefaultShortcuts;
import com.javelin.settings.Settings;
import com.javelin.ui.ai.AiController;
import com.javelin.ui.customize.SettingsController;
import com.javelin.ui.customize.ThemeController;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.editor.SearchController;
import com.javelin.ui.language.LanguageController;
import com.javelin.ui.project.ProjectSession;
import com.javelin.ui.project.ProjectTree;
import com.javelin.ui.run.RunController;
import com.javelin.ui.shell.BottomPanel;
import com.javelin.ui.shell.KeyBinder;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.fxmisc.richtext.CodeArea;

/**
 * The menu bar: every command's label, id, default shortcut and the controller method it runs.
 * Nothing is decided here; each item only forwards to the feature that owns the behaviour.
 */
public final class MainMenu {
  private final KeyBinder binder;
  private final EditorTabs editors;
  private final SearchController search;
  private final ProjectSession session;
  private final ProjectTree tree;
  private final BottomPanel bottom;
  private final ThemeController themes;
  private final LanguageController language;
  private final RunController run;
  private final SettingsController settings;
  private final AiController ai;

  public MainMenu(
      KeyBinder binder,
      EditorTabs editors,
      SearchController search,
      ProjectSession session,
      ProjectTree tree,
      BottomPanel bottom,
      ThemeController themes,
      LanguageController language,
      RunController run,
      SettingsController settings,
      AiController ai) {
    this.binder = binder;
    this.editors = editors;
    this.search = search;
    this.session = session;
    this.tree = tree;
    this.bottom = bottom;
    this.themes = themes;
    this.language = language;
    this.run = run;
    this.settings = settings;
    this.ai = ai;
  }

  public MenuBar create(Stage stage) {
    Menu file = new Menu("File");
    file.getItems()
        .addAll(
            binder.bound("File", "New", "file.new", "", editors::newDocument),
            binder.bound(
                "File",
                "Open File...",
                "file.openFile",
                "",
                () -> editors.chooseAndOpenFile(stage)),
            binder.bound(
                "File",
                "Open Directory...",
                "file.openDirectory",
                "",
                () -> session.chooseDirectory(stage)),
            session.recentProjectsMenu(),
            new SeparatorMenuItem(),
            binder.bound(
                "File",
                "Save",
                "file.save",
                DefaultShortcuts.SAVE,
                () -> editors.saveCurrent(false)),
            binder.bound(
                "File",
                "Save As...",
                "file.saveAs",
                DefaultShortcuts.SAVE_AS,
                () -> editors.saveCurrent(true)),
            binder.bound("File", "Close", "file.close", "", editors::closeCurrentTab),
            new SeparatorMenuItem(),
            binder.bound(
                "File",
                "Exit",
                "file.exit",
                "",
                () -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))));

    Menu edit = new Menu("Edit");
    edit.getItems()
        .addAll(
            binder.bound(
                "Edit",
                "Undo",
                "edit.undo",
                DefaultShortcuts.UNDO_DEFAULT,
                () -> editors.currentEditor().ifPresent(CodeArea::undo)),
            binder.bound(
                "Edit",
                "Redo",
                "edit.redo",
                DefaultShortcuts.REDO_DEFAULT,
                () -> editors.currentEditor().ifPresent(CodeArea::redo)),
            new SeparatorMenuItem(),
            binder.bound("Edit", "Search", "edit.search", DefaultShortcuts.SEARCH, search::search),
            binder.bound(
                "Edit", "Find Next", "edit.findNext", DefaultShortcuts.FIND_NEXT, search::findNext),
            binder.bound(
                "Edit",
                "Find Previous",
                "edit.findPrevious",
                DefaultShortcuts.FIND_PREVIOUS,
                search::findPrevious),
            binder.bound(
                "Edit", "Replace", "edit.replace", DefaultShortcuts.REPLACE, search::replace),
            binder.bound(
                "Edit",
                "Go to Line",
                "edit.goToLine",
                DefaultShortcuts.GO_TO_LINE,
                search::goToLine),
            binder.bound(
                "Edit",
                "Go to File",
                "edit.goToFile",
                DefaultShortcuts.GO_TO_FILE,
                search::goToFile),
            binder.bound(
                "Edit",
                "Match Bracket",
                "edit.matchBracket",
                DefaultShortcuts.MATCH_BRACKET,
                search::matchBracket),
            binder.bound(
                "Edit",
                "Add Cursor",
                "edit.addCursor",
                DefaultShortcuts.ADD_CURSOR,
                editors::addCursor));

    Menu view = new Menu("View");
    view.getItems()
        .addAll(
            binder.bound("View", "Focus Mode", "view.focusMode", "", tree::toggleVisible),
            new SeparatorMenuItem(),
            binder.bound(
                "View",
                "Fold Selected",
                "view.fold",
                "",
                () -> editors.currentEditor().ifPresent(CodeArea::foldSelectedParagraphs)),
            binder.bound(
                "View",
                "Unfold Current",
                "view.unfold",
                "",
                () ->
                    editors
                        .currentEditor()
                        .ifPresent(a -> a.unfoldParagraphs(a.getCurrentParagraph()))),
            new SeparatorMenuItem(),
            binder.bound(
                "View",
                "Toggle Bottom Panel",
                "view.bottomPanel",
                DefaultShortcuts.BOTTOM_PANEL,
                bottom::toggle),
            binder.bound(
                "View", "Output", "view.output", "", () -> bottom.show(bottom.outputTab())),
            binder.bound(
                "View", "Problems", "view.problems", "", () -> bottom.show(bottom.problemsTab())),
            binder.bound(
                "View",
                "References",
                "view.references",
                "",
                () -> bottom.show(bottom.referencesTab())),
            binder.bound(
                "View",
                "Terminal",
                "view.terminal",
                DefaultShortcuts.TERMINAL,
                () -> {
                  bottom.show(bottom.terminalTab());
                  bottom.terminals().focusInput();
                }),
            binder.bound(
                "View",
                "New Terminal",
                "view.newTerminal",
                DefaultShortcuts.NEW_TERMINAL,
                () -> {
                  bottom.show(bottom.terminalTab());
                  bottom.terminals().newTerminal();
                }),
            new SeparatorMenuItem(),
            binder.bound(
                "View", "Zoom In", "view.zoomIn", DefaultShortcuts.ZOOM_IN, () -> editors.zoom(1)),
            binder.bound(
                "View",
                "Zoom Out",
                "view.zoomOut",
                DefaultShortcuts.ZOOM_OUT,
                () -> editors.zoom(-1)),
            binder.bound(
                "View",
                "Reset Zoom",
                "view.zoomReset",
                DefaultShortcuts.ZOOM_RESET,
                () -> editors.zoom(0)),
            new SeparatorMenuItem(),
            themes.menu());

    Menu languageMenu = new Menu("Language");
    languageMenu
        .getItems()
        .addAll(
            binder.bound(
                "Language",
                "Start Language Server...",
                "language.startServer",
                "",
                language::startServer),
            binder.bound(
                "Language",
                "Stop Language Server",
                "language.stopServer",
                "",
                language::stopServer),
            new SeparatorMenuItem(),
            binder.bound(
                "Language",
                "Complete",
                "language.complete",
                DefaultShortcuts.COMPLETE,
                language::complete),
            binder.bound(
                "Language",
                "Show Hover Info",
                "language.hover",
                DefaultShortcuts.HOVER,
                language::hover),
            binder.bound(
                "Language",
                "Signature Help",
                "language.signatureHelp",
                DefaultShortcuts.SIGNATURE_HELP,
                language::signatureHelp),
            binder.bound(
                "Language",
                "Go to Definition",
                "language.goToDefinition",
                DefaultShortcuts.GO_TO_DEFINITION,
                language::goToDefinition),
            binder.bound(
                "Language",
                "Find References",
                "language.findReferences",
                DefaultShortcuts.FIND_REFERENCES,
                language::findReferences),
            binder.bound(
                "Language",
                "Document Symbols",
                "language.documentSymbols",
                DefaultShortcuts.DOCUMENT_SYMBOLS,
                language::documentSymbols),
            new SeparatorMenuItem(),
            binder.bound(
                "Language",
                "Rename Symbol...",
                "language.rename",
                DefaultShortcuts.RENAME,
                language::renameSymbol),
            binder.bound(
                "Language",
                "Format Document",
                "language.format",
                DefaultShortcuts.FORMAT,
                language::formatDocument));

    Menu aiMenu = new Menu("AI");
    aiMenu
        .getItems()
        .addAll(
            binder.bound("AI", "Explain Selection", "ai.explain", "", ai::explain),
            binder.bound("AI", "Debug Selection", "ai.debug", "", ai::debug),
            binder.bound("AI", "Generate Tests", "ai.generateTests", "", ai::generateTests),
            binder.bound("AI", "Propose Change...", "ai.proposeChange", "", ai::proposeChange),
            new SeparatorMenuItem(),
            binder.bound("AI", "Project Request...", "ai.projectRequest", "", ai::projectRequest),
            binder.bound(
                "AI",
                "Undo Last Project Change",
                "ai.undoProjectChange",
                "",
                ai::undoProjectChange),
            new SeparatorMenuItem(),
            binder.bound("AI", "Choose Model...", "ai.chooseModel", "", ai::chooseModel),
            binder.bound("AI", "Cancel AI Request", "ai.cancel", "", ai::cancel));

    Menu runMenu = new Menu("Run");
    runMenu
        .getItems()
        .addAll(
            binder.bound("Run", "Build", "run.build", DefaultShortcuts.BUILD, run::build),
            binder.bound(
                "Run",
                "Run Command...",
                "run.runCommand",
                DefaultShortcuts.RUN_COMMAND,
                run::runCommandDialog),
            new SeparatorMenuItem(),
            binder.bound(
                "Run",
                "Stop All Processes",
                "run.stopAll",
                DefaultShortcuts.STOP_ALL,
                run::stopAll),
            binder.bound("Run", "Processes...", "run.processes", "", run::showProcesses));

    Menu settingsMenu = new Menu("Settings");
    settingsMenu
        .getItems()
        .addAll(
            binder.bound(
                "Settings",
                "Settings...",
                "settings.open",
                DefaultShortcuts.SETTINGS,
                settings::openDialog),
            binder.bound(
                "Settings",
                "Project Settings...",
                "settings.project",
                "",
                settings::openProjectDialog),
            new SeparatorMenuItem(),
            binder.bound(
                "Settings",
                "Open User Settings File",
                "settings.openUserFile",
                "",
                () -> settings.openFile(Settings.Scope.USER)),
            binder.bound(
                "Settings",
                "Open Project Settings File",
                "settings.openProjectFile",
                "",
                () -> settings.openFile(Settings.Scope.PROJECT)),
            binder.bound("Settings", "Reload Settings", "settings.reload", "", settings::reload));
    return new MenuBar(file, edit, view, languageMenu, aiMenu, runMenu, settingsMenu);
  }
}
