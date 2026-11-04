package com.javelin.ui;

import java.util.function.Consumer;
import javafx.event.EventHandler;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;

/**
 * A read-only information box under the caret. It closes on the next key press or click in the
 * editor.
 */
public final class HoverPopup {
  private final Popup popup = new Popup();
  private final Label label = new Label();
  private final ScrollPane pane = new ScrollPane(label);
  private CodeArea editor;
  private final EventHandler<KeyEvent> onKey =
      e -> {
        if (!e.getCode().isModifierKey()) hide();
      };
  private final EventHandler<MouseEvent> onMouse = e -> hide();

  public HoverPopup() {
    label.setWrapText(true);
    label.setMaxWidth(560);
    pane.setFitToWidth(true);
    pane.setMaxSize(580, 280);
    pane.setFocusTraversable(false);
    pane.getStyleClass().add("popup-pane");
    label.getStyleClass().add("popup-text");
    popup.getContent().add(pane);
    popup.setAutoHide(true);
    popup.setOnHidden(e -> detach());
  }

  public void show(CodeArea editor, String text, Consumer<Parent> styler) {
    hide();
    var bounds = editor.getCaretBounds();
    if (bounds.isEmpty() || editor.getScene() == null) return;
    this.editor = editor;
    label.setText(text);
    styler.accept(pane);
    editor.addEventFilter(KeyEvent.KEY_PRESSED, onKey);
    editor.addEventFilter(MouseEvent.MOUSE_PRESSED, onMouse);
    popup.show(editor.getScene().getWindow(), bounds.get().getMinX(), bounds.get().getMaxY() + 2);
  }

  public void hide() {
    popup.hide();
    detach();
  }

  private void detach() {
    if (editor == null) return;
    editor.removeEventFilter(KeyEvent.KEY_PRESSED, onKey);
    editor.removeEventFilter(MouseEvent.MOUSE_PRESSED, onMouse);
    editor = null;
  }
}
