package com.javelin.ui;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;

/**
 * Lists the locations returned by Find References (or an ambiguous Go to Definition). Double-click
 * opens one. FX thread only.
 */
public final class ReferencesPanel extends BorderPane {
  /**
   * A location in a file. Line and column are 1-based; {@code preview} is the text of that line.
   */
  public record Reference(File file, int line, int column, String preview) {
    @Override
    public String toString() {
      return file.getName() + ":" + line + ":" + column + "    " + preview.strip();
    }
  }

  private final ListView<Reference> list = new ListView<>();
  private final Label title = new Label();
  private Consumer<Reference> onOpen = r -> {};

  public ReferencesPanel() {
    list.setPlaceholder(new Label("No references. Use Language > Find References."));
    list.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(Reference reference, boolean empty) {
                super.updateItem(reference, empty);
                setText(empty || reference == null ? null : reference.toString());
              }
            });
    list.setOnMouseClicked(
        e -> {
          Reference selected = list.getSelectionModel().getSelectedItem();
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selected != null)
            onOpen.accept(selected);
        });
    title.setStyle("-fx-padding: 4 8 4 8; -fx-font-weight: bold;");
    setTop(title);
    setCenter(list);
  }

  public void setOnOpen(Consumer<Reference> handler) {
    onOpen = handler;
  }

  public void show(String heading, List<Reference> references) {
    title.setText(heading);
    list.getItems().setAll(references);
  }

  public ObservableList<Reference> items() {
    return list.getItems();
  }
}
