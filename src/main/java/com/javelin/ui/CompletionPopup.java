package com.javelin.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Parent;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;

/**
 * A completion list under the caret. It never takes focus: while it is open, Up/Down/Enter/Tab/Esc
 * are intercepted from the editor and everything else types normally, narrowing the list by the
 * text typed since {@code start}.
 */
public final class CompletionPopup {
  /**
   * One candidate. {@code filter} is what typed text is matched against; {@code payload} is handed
   * back on accept.
   */
  public record Entry(String label, String detail, String filter, Object payload) {}

  private static final int MAX_ROWS = 8;
  private static final double ROW_HEIGHT = 24;

  private final Popup popup = new Popup();
  private final ListView<Entry> list = new ListView<>();
  private CodeArea editor;
  private List<Entry> all = List.of();
  private int start;
  private Consumer<Entry> onAccept = e -> {};
  private final EventHandler<KeyEvent> keys = this::handleKey;
  private final ChangeListener<Number> caretListener = (obs, was, now) -> refilter();

  public CompletionPopup() {
    list.getStyleClass().add("popup-pane");
    list.setFocusTraversable(false);
    list.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(
                    empty || entry == null
                        ? null
                        : entry.detail() == null || entry.detail().isBlank()
                            ? entry.label()
                            : entry.label() + "    " + entry.detail());
              }
            });
    list.setOnMouseClicked(
        e -> {
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) accept();
        });
    popup.getContent().add(list);
    popup.setAutoHide(true);
    popup.setOnHidden(e -> detach());
  }

  public boolean isShowing() {
    return popup.isShowing();
  }

  /**
   * Shows the candidates, anchored under the caret. {@code styler} applies the app's theme to the
   * popup's own scene.
   */
  public void show(
      CodeArea editor,
      List<Entry> entries,
      int start,
      Consumer<Entry> onAccept,
      Consumer<Parent> styler) {
    hide();
    this.editor = editor;
    this.all = entries;
    this.start = start;
    this.onAccept = onAccept;
    styler.accept(list);
    list.getItems().setAll(filtered());
    if (list.getItems().isEmpty() || editor.getScene() == null) return;
    list.getSelectionModel().selectFirst();
    resize();
    var bounds = editor.getCaretBounds();
    if (bounds.isEmpty()) return;
    editor.addEventFilter(KeyEvent.KEY_PRESSED, keys);
    editor.caretPositionProperty().addListener(caretListener);
    popup.show(editor.getScene().getWindow(), bounds.get().getMinX(), bounds.get().getMaxY());
  }

  public void hide() {
    popup.hide();
    detach();
  }

  private void detach() {
    if (editor == null) return;
    editor.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
    editor.caretPositionProperty().removeListener(caretListener);
  }

  private void handleKey(KeyEvent e) {
    KeyCode code = e.getCode();
    if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) return;
    switch (code) {
      case DOWN -> {
        move(1);
        e.consume();
      }
      case UP -> {
        move(-1);
        e.consume();
      }
      case ENTER, TAB -> {
        accept();
        e.consume();
      }
      case ESCAPE -> {
        hide();
        e.consume();
      }
      case LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN -> hide();
      default -> {}
    }
  }

  private void move(int delta) {
    int size = list.getItems().size();
    if (size == 0) return;
    int next = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
    list.getSelectionModel().select(next);
    list.scrollTo(next);
  }

  private void accept() {
    Entry selected = list.getSelectionModel().getSelectedItem();
    Consumer<Entry> handler = onAccept;
    hide();
    if (selected != null) handler.accept(selected);
  }

  private void refilter() {
    if (editor == null) return;
    if (editor.getCaretPosition() < start) {
      hide();
      return;
    }
    List<Entry> matches = filtered();
    if (matches.isEmpty()) {
      hide();
      return;
    }
    Entry keep = list.getSelectionModel().getSelectedItem();
    list.getItems().setAll(matches);
    if (keep != null && matches.contains(keep)) list.getSelectionModel().select(keep);
    else list.getSelectionModel().selectFirst();
    resize();
  }

  /**
   * Entries whose filter text starts with what was typed since {@code start}; hides itself if that
   * text stops being a word.
   */
  private List<Entry> filtered() {
    int caret = Math.max(start, Math.min(editor.getCaretPosition(), editor.getLength()));
    String typed = editor.getText(start, caret);
    for (int i = 0; i < typed.length(); i++)
      if (!Character.isJavaIdentifierPart(typed.charAt(i))) return List.of();
    String lower = typed.toLowerCase();
    List<Entry> out = new ArrayList<>();
    for (Entry entry : all) if (entry.filter().toLowerCase().startsWith(lower)) out.add(entry);
    return out;
  }

  private void resize() {
    list.setPrefWidth(440);
    list.setPrefHeight(Math.min(list.getItems().size(), MAX_ROWS) * ROW_HEIGHT + 4);
  }
}
