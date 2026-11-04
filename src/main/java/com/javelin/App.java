package com.javelin;

import com.javelin.build.ProcessManager;
import com.javelin.build.ShellEnvironment;
import com.javelin.io.CrashLog;
import com.javelin.language.DiagnosticStore;
import com.javelin.launch.LaunchArguments;
import com.javelin.ui.Dialogs;
import com.javelin.ui.ai.AiController;
import com.javelin.ui.customize.SettingsController;
import com.javelin.ui.customize.ThemeController;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.editor.SearchController;
import com.javelin.ui.language.LanguageController;
import com.javelin.ui.menu.MainMenu;
import com.javelin.ui.project.ProjectSession;
import com.javelin.ui.project.ProjectTree;
import com.javelin.ui.recovery.RecoveryController;
import com.javelin.ui.run.RunController;
import com.javelin.ui.shell.BottomPanel;
import com.javelin.ui.shell.KeyBinder;
import com.javelin.ui.shell.LayoutMemory;
import com.javelin.ui.shell.WorkArea;
import com.javelin.ui.shell.Workbench;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * The JavaFX application and the one place where the window is put together. It creates each
 * feature, connects them (see {@code ARCHITECTURE.md}) and runs the window's lifecycle; it decides
 * nothing itself. The rules that keep it that way are checked by {@code ArchitectureTest}.
 */
public final class App extends Application {
  private final Workbench bench = new Workbench(Preferences.userNodeForPackage(App.class));
  private final ProcessManager processes = new ProcessManager();
  private final DiagnosticStore diagnostics = new DiagnosticStore();
  private final EditorTabs editors = new EditorTabs(bench, diagnostics);
  private final BottomPanel bottom =
      new BottomPanel(editors.node(), processes, bench::projectDirectory);
  private final ProjectTree tree = new ProjectTree(bench, editors);
  private final KeyBinder binder = new KeyBinder(bench.keys());
  private final ThemeController themes = new ThemeController(bench);
  private final SettingsController settings =
      new SettingsController(bench, editors, bottom, themes, binder, tree::refresh);
  private final ProjectSession session = new ProjectSession(bench, editors, bottom, settings);
  private final SearchController search = new SearchController(bench, editors);
  private final RunController run = new RunController(bench, editors, bottom, processes);
  private final LanguageController language =
      new LanguageController(bench, editors, bottom, diagnostics, themes::stylePopup);
  private final AiController ai = new AiController(bench, editors, bottom);
  private final RecoveryController recovery = new RecoveryController(bench, editors);

  @Override
  public void start(Stage stage) {
    ShellEnvironment
        .warmUp(); // read the login shell's PATH etc. in the background: Finder-launched apps start
    // with launchd's
    CrashLog.install(
        bench.settings().userFile().getParent().resolve("crashes"),
        report ->
            Platform.runLater(
                () ->
                    bench.status(
                        report == null
                            ? "An unexpected error occurred."
                            : "An unexpected error occurred; a report was saved to " + report)));
    bench.attach(stage, null);
    Dialogs.setOwner(stage);
    stage.setTitle(Branding.FULL_NAME);
    settings.applyIcon();

    // Who hears about documents, in the order they must react (a saved file is told to the language
    // server, then the tree, then settings).
    editors.addListener(language);
    editors.addListener(tree);
    editors.addListener(settings);
    editors.addListener(recovery);
    // Who re-applies what after settings change.
    settings.onApplied(search::reloadSettings);
    settings.onApplied(editors::applyPreferences);
    settings.onApplied(tree::refresh);

    BorderPane root = new BorderPane();
    root.setTop(
        new MainMenu(
                binder, editors, search, session, tree, bottom, themes, language, run, settings, ai)
            .create(stage));
    WorkArea workArea = new WorkArea(tree.sidebar(), bottom.node());
    LayoutMemory layout = new LayoutMemory(bench.settings(), workArea, bottom, bench::status);
    root.setCenter(workArea.node());
    Region statusSpacer = new Region();
    HBox.setHgrow(statusSpacer, Priority.ALWAYS);
    HBox statusBar =
        new HBox(8, bench.statusLabel(), statusSpacer, ai.statusLabel(), language.statusLabel());
    statusBar.setPadding(new Insets(4, 8, 4, 8));
    root.setBottom(statusBar);
    Scene scene = new Scene(root, 1200, 760);
    bench.attach(stage, scene);
    editors.attachTo(scene);
    var css = getClass().getResource("/styles/editor.css");
    if (css != null) scene.getStylesheets().add(css.toExternalForm());
    themes.install();
    binder.apply();
    stage.setScene(scene);
    stage.setOnCloseRequest(
        event -> {
          if (!editors.confirmCloseDirtyTabs()) event.consume();
          else {
            recovery.stopAutosave();
            layout.flush();
          }
        });
    stage.show();
    tree.startWatching();
    settings.reportProblems(false);
    recovery.start();
    openFromCommandLine();
  }

  // ---- started from the terminal: `Javelin [directory]` (see packaging/macos) ----

  /**
   * Opens the directory given on the command line. The {@code Javelin} command starts a new
   * instance of this app for every folder ({@code open -n}), so each window has exactly one project
   * and nothing is handed to a running window.
   */
  private void openFromCommandLine() {
    LaunchArguments.Result launch =
        LaunchArguments.parse(getParameters().getRaw(), Path.of("").toAbsolutePath());
    launch.problem().ifPresent(bench::status);
    launch
        .directory()
        .ifPresent(
            directory -> {
              // A window started only to open a folder that is already open elsewhere has nothing
              // else to show: close it.
              if (!session.open(directory.toFile()) && bench.projectDirectory() == null)
                Platform.exit();
            });
  }

  @Override
  public void stop() {
    session.dispose();
    tree.dispose();
    recovery.stopAutosave();
    bench.background().shutdown();
    language.dispose();
    ai.dispose();
    processes.stopAll();
    editors.dispose();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
