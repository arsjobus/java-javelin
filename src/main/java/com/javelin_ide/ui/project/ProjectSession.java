package com.javelin_ide.ui.project;

import com.javelin_ide.Branding;
import com.javelin_ide.io.ProjectChange;
import com.javelin_ide.io.RecentProjects;
import com.javelin_ide.launch.ProjectInstances;
import com.javelin_ide.ui.Dialogs;
import com.javelin_ide.ui.customize.SettingsController;
import com.javelin_ide.ui.editor.EditorTabs;
import com.javelin_ide.ui.shell.BottomPanel;
import com.javelin_ide.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/** Which project this window shows: opening one (closing the old one's files first), recent projects, one window per folder. */
public final class ProjectSession {
    private final Workbench bench;
    private final EditorTabs editors;
    private final BottomPanel bottom;
    private final SettingsController settings;
    private final RecentProjects recentStore;
    // Which folders other Javelin IDE windows have open (see launch.ProjectInstances); this window's own claim is released when it switches project or exits.
    private ProjectInstances instances;
    private ProjectInstances.Claim projectClaim;

    public ProjectSession(Workbench bench, EditorTabs editors, BottomPanel bottom, SettingsController settings) {
        this.bench = bench;
        this.editors = editors;
        this.bottom = bottom;
        this.settings = settings;
        this.recentStore = new RecentProjects(bench.preferences());
    }

    public void chooseDirectory(Window owner) {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Open Project Directory");
        File directory = Dialogs.guarded(() -> chooser.showDialog(owner)); if (directory != null) open(directory);
    }

    /** Opens {@code directory} as this window's project. Returns false when nothing was opened (it is gone, or the user declined). */
    public boolean open(File directory) {
        if (!directory.isDirectory()) { bench.showError("Could not open project", "The selected directory no longer exists."); return false; }
        File nextProjectDirectory = directory.getAbsoluteFile();
        File projectDirectory = bench.projectDirectory();
        // The previous project's files go first: each unsaved one is saved or discarded as the user chooses, then closed.
        if (ProjectChange.replacesOpenProject(projectDirectory, nextProjectDirectory) && !editors.closeAllTabs()) {
            bench.status("Not opened: the previous project still has open files");
            return false;
        }
        if (!claimProject(nextProjectDirectory)) return false;
        ProjectChange.closeSessionsIfChanged(projectDirectory, nextProjectDirectory, bottom.terminals()::closeAll);
        bench.projectDirectory(nextProjectDirectory);
        String projectName = nextProjectDirectory.getName();
        bench.stage().setTitle(Branding.windowTitle(projectName.isBlank() ? nextProjectDirectory.getAbsolutePath() : projectName));
        bench.settings().setProject(nextProjectDirectory.toPath()); // reads <project>/.javelin-ide/settings.conf if there is one
        settings.apply();
        recentStore.add(nextProjectDirectory);
        bench.status("Project: " + nextProjectDirectory.getAbsolutePath());
        settings.reportProblems(false);
        return true;
    }

    /**
     * Marks {@code directory} as open in this window. When another Javelin IDE window already has it, asks before opening it
     * a second time, because two windows would race on the same files. Never blocks opening because of a failure to check.
     */
    private boolean claimProject(File directory) {
        if (projectClaim != null && projectClaim.project().equals(realPathOrSelf(directory))) return true; // reopening this window's own project
        try {
            if (instances == null) {
                instances = new ProjectInstances(bench.settings().userFile().getParent().resolve(ProjectInstances.DIRECTORY_NAME));
                bench.background().execute(() -> instances.prune(java.time.Duration.ofDays(30)));
            }
            Optional<ProjectInstances.Claim> claim = instances.claim(directory.toPath());
            if (claim.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Project already open");
                alert.setHeaderText(directory.getName() + " is already open in another " + Branding.FULL_NAME + " window.");
                alert.setContentText("Two windows on one folder can overwrite each other's changes. Open it here as well?");
                ButtonType openAnyway = new ButtonType("Open Anyway"), cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(openAnyway, cancel);
                if (Dialogs.show(alert).orElse(cancel) != openAnyway) { bench.status("Not opened: already open in another window"); return false; }
                if (projectClaim != null) { projectClaim.close(); projectClaim = null; } // this window no longer shows that project
                return true; // and it does not own the new one: the other window keeps the claim
            }
            if (projectClaim != null) projectClaim.close();
            projectClaim = claim.get();
        } catch (IOException e) {
            bench.status("Could not check whether the project is open in another window: " + e.getMessage());
        }
        return true;
    }

    private static Path realPathOrSelf(File directory) {
        try { return directory.toPath().toRealPath(); } catch (IOException e) { return directory.toPath().toAbsolutePath().normalize(); }
    }

    public Menu recentProjectsMenu() {
        Menu recent = new Menu("Recent Projects");
        recent.setOnShowing(e -> populateRecentProjectsMenu(recent));
        populateRecentProjectsMenu(recent);
        return recent;
    }

    private void populateRecentProjectsMenu(Menu recent) {
        recent.getItems().clear();
        List<String> projects = recentStore.list();
        if (projects.isEmpty()) {
            MenuItem empty = new MenuItem("No recent projects");
            empty.setDisable(true);
            recent.getItems().add(empty);
        } else {
            for (String path : projects) {
                File directory = new File(path);
                MenuItem item = new MenuItem(directory.getName().isBlank() ? directory.getAbsolutePath() : directory.getName());
                item.setOnAction(e -> open(directory));
                recent.getItems().add(item);
            }
            recent.getItems().add(new SeparatorMenuItem());
        }
        MenuItem clear = new MenuItem("Clear Recent Projects");
        clear.setDisable(projects.isEmpty());
        clear.setOnAction(e -> {
            recentStore.clear();
            populateRecentProjectsMenu(recent);
        });
        recent.getItems().add(clear);
    }

    /** Releases this window's claim on its project (when the window closes). */
    public void dispose() { if (projectClaim != null) projectClaim.close(); }
}
