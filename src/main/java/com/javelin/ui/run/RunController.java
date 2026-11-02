package com.javelin.ui.run;

import com.javelin.build.BuildSystem;
import com.javelin.build.ManagedProcess;
import com.javelin.build.Problem;
import com.javelin.build.ProblemParser;
import com.javelin.build.ProcessManager;
import com.javelin.build.ShellCommand;
import com.javelin.ui.Dialogs;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.shell.BottomPanel;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;

/** The Run menu: build the project, run a command, see and stop what is running. Commands are always shown or confirmed first. */
public final class RunController {
    private final Workbench bench;
    private final EditorTabs editors;
    private final BottomPanel bottom;
    private final ProcessManager processes;

    public RunController(Workbench bench, EditorTabs editors, BottomPanel bottom, ProcessManager processes) {
        this.bench = bench;
        this.editors = editors;
        this.bottom = bottom;
        this.processes = processes;
        bottom.problems().setOnOpen(this::openProblem);
    }

    public void build() {
        if (!bench.requireProject("Build")) return;
        File projectDirectory = bench.projectDirectory();
        String customBuild = bench.settings().get("build.command", "").trim();
        if (!customBuild.isEmpty()) { bottom.problems().clear(); bottom.output().append("Using build.command from settings"); runCommand("Build", customBuild); return; }
        Optional<BuildSystem> system = BuildSystem.detect(projectDirectory);
        if (system.isEmpty()) { bench.showError("Build", "No supported build file found (pom.xml, build.gradle, Makefile, Cargo.toml, package.json). Use Run > Run Command... instead."); return; }
        bottom.problems().clear();
        runCommand(system.get().name() + " build", system.get().buildCommand());
    }

    public void runCommandDialog() {
        if (!bench.requireProject("Run Command")) return;
        File projectDirectory = bench.projectDirectory();
        String key = "run.command." + Integer.toHexString(projectDirectory.getAbsolutePath().hashCode());
        String configured = bench.settings().get("run.command", "").trim(); // a run.command in settings is the project's own default
        String suggestion = !configured.isEmpty() ? configured : bench.preferences().get(key, BuildSystem.detect(projectDirectory).map(BuildSystem::suggestedRunCommand).orElse(""));
        TextInputDialog d = new TextInputDialog(suggestion);
        d.setTitle("Run Command"); d.setHeaderText("Command to run in " + projectDirectory.getAbsolutePath()); d.setContentText("Command:");
        Dialogs.show(d).filter(s -> !s.isBlank()).ifPresent(command -> { bench.preferences().put(key, command.trim()); runCommand("Run", command.trim()); });
    }

    private void runCommand(String name, String commandLine) {
        File projectDirectory = bench.projectDirectory();
        long unsaved = editors.unsavedCount();
        bottom.show(bottom.outputTab());
        bottom.output().append("$ " + commandLine + "    (in " + projectDirectory.getAbsolutePath() + ")");
        if (unsaved > 0) bottom.output().append("Note: " + unsaved + " unsaved file(s) are not included; the command uses files on disk.");
        ProblemParser parser = new ProblemParser(); // stateful (multi-line diagnostics); only touched on the FX thread
        try {
            processes.start(name, ShellCommand.wrap(commandLine), projectDirectory, new ProcessManager.Listener() {
                @Override public void onLine(ManagedProcess p, String line) {
                    Platform.runLater(() -> { bottom.output().append(line); parser.accept(line).ifPresent(bottom.problems()::add); });
                }
                @Override public void onExit(ManagedProcess p, int code) {
                    Platform.runLater(() -> { bottom.output().append("[" + name + " exited with code " + code + "]"); bench.status(name + " exited with code " + code); });
                }
            });
            bench.status("Running: " + commandLine);
        } catch (IOException ex) { bench.showError("Could not start command", ex.getMessage()); }
    }

    private void openProblem(Problem problem) {
        File file = new File(problem.file());
        if (!file.isAbsolute() && bench.projectDirectory() != null) file = new File(bench.projectDirectory(), problem.file());
        if (file.isFile()) editors.openFile(file, problem.line(), problem.column()); else bench.status("File not found: " + problem.file());
    }

    public void stopAll() {
        int count = processes.running().size();
        processes.stopAll();
        bench.status(count == 0 ? "No running processes" : "Stopping " + count + " process(es)");
    }

    public void showProcesses() {
        List<ManagedProcess> running = processes.running();
        if (running.isEmpty()) { bench.status("No running processes"); return; }
        ChoiceDialog<ManagedProcess> d = new ChoiceDialog<>(running.get(running.size() - 1), running);
        d.setTitle("Processes"); d.setHeaderText("Select a process to stop"); d.setContentText("Process:");
        Dialogs.show(d).ifPresent(processes::stop);
    }
}
