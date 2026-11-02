package com.javelin.ui;

import com.javelin.build.Problem;
import javafx.collections.ObservableList;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lists diagnostics from build output and from language servers. Each source (the build, or one file's language
 * diagnostics) owns its own entries, so a new build does not wipe language diagnostics and vice versa.
 * Double-click opens the location. Call from the FX thread only.
 */
public final class ProblemsPanel extends BorderPane {
    /** The source used by {@link #add} and {@link #clear}. */
    public static final String BUILD = "build";

    private final ListView<Problem> list = new ListView<>();
    private final Map<String, List<Problem>> sources = new LinkedHashMap<>();
    private Consumer<Problem> onOpen = p -> {};

    public ProblemsPanel() {
        list.setPlaceholder(new javafx.scene.control.Label("No problems"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Problem problem, boolean empty) {
                super.updateItem(problem, empty);
                setText(empty || problem == null ? null : problem.toString());
                getStyleClass().removeAll("problem-error", "problem-warning");
                if (!empty && problem != null) getStyleClass().add(problem.severity() == Problem.Severity.ERROR ? "problem-error" : "problem-warning");
            }
        });
        list.setOnMouseClicked(e -> {
            Problem selected = list.getSelectionModel().getSelectedItem();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selected != null) onOpen.accept(selected);
        });
        setCenter(list);
    }

    public void setOnOpen(Consumer<Problem> handler) { onOpen = handler; }

    /** Adds a build problem unless an identical one is already listed (build tools often repeat errors in their summary). */
    public void add(Problem problem) {
        List<Problem> build = sources.computeIfAbsent(BUILD, k -> new ArrayList<>());
        if (build.contains(problem)) return;
        build.add(problem);
        refresh();
    }

    /** Clears build problems only. */
    public void clear() { replace(BUILD, List.of()); }

    /** Replaces everything one source reported; an empty list removes the source. */
    public void replace(String source, List<Problem> problems) {
        if (problems.isEmpty()) { if (sources.remove(source) == null) return; }
        else sources.put(source, new ArrayList<>(problems));
        refresh();
    }

    private void refresh() {
        List<Problem> all = new ArrayList<>();
        sources.values().forEach(all::addAll);
        list.getItems().setAll(all);
    }

    public ObservableList<Problem> items() { return list.getItems(); }
}
