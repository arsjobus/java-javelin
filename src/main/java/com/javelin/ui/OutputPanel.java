package com.javelin.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** Read-only log of command output. Call from the FX thread only. */
public final class OutputPanel extends BorderPane {
  private static final int MAX_CHARS = 400_000;
  private static final int KEEP_CHARS = 300_000;
  private final TextArea area = new TextArea();

  public OutputPanel() {
    area.setEditable(false);
    area.setWrapText(false);
    area.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 13px;");
    Button clear = new Button("Clear");
    clear.setOnAction(e -> clear());
    HBox bar = new HBox(clear);
    bar.setPadding(new Insets(4));
    setTop(bar);
    setCenter(area);
  }

  public void append(String line) {
    area.appendText(line + "\n");
    if (area.getLength() > MAX_CHARS) area.deleteText(0, area.getLength() - KEEP_CHARS);
  }

  public void clear() {
    area.clear();
  }
}
