package com.javelin.ui;

import com.javelin.settings.EditorPreferences;
import com.javelin.settings.Keybindings;
import com.javelin.settings.Settings;
import com.javelin.settings.Themes.Theme;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * The Settings and Project Settings dialogs. Both edit the same plain files a user can edit by
 * hand: this class only reads and writes {@link Settings}, and returns true when something was
 * saved so the caller can re-apply. Dialogs are modal and open only on request.
 */
public final class SettingsDialog {
  private SettingsDialog() {}

  // ---- user settings: editor, appearance, keybindings ----

  public static boolean showUser(
      Window owner,
      Settings settings,
      Keybindings keys,
      List<Theme> themes,
      String currentThemeId) {
    EditorPreferences defaults =
        new EditorPreferences(
            EditorPreferences.DEFAULT_FONT,
            EditorPreferences.DEFAULT_FONT_SIZE,
            EditorPreferences.DEFAULT_TAB_SIZE,
            true,
            false,
            true,
            true);

    TextField font =
        new TextField(valueOr(settings.getUser("editor.fontFamily"), defaults.fontFamily()));
    Spinner<Integer> fontSize =
        new Spinner<>(
            EditorPreferences.MIN_FONT_SIZE,
            EditorPreferences.MAX_FONT_SIZE,
            intOr(
                settings.getUser("editor.fontSize"),
                defaults.fontSize(),
                EditorPreferences.MIN_FONT_SIZE,
                EditorPreferences.MAX_FONT_SIZE));
    Spinner<Integer> tabSize =
        new Spinner<>(
            EditorPreferences.MIN_TAB_SIZE,
            EditorPreferences.MAX_TAB_SIZE,
            intOr(
                settings.getUser("editor.tabSize"),
                defaults.tabSize(),
                EditorPreferences.MIN_TAB_SIZE,
                EditorPreferences.MAX_TAB_SIZE));
    CheckBox insertSpaces = new CheckBox("Insert spaces when Tab is pressed");
    insertSpaces.setSelected(
        boolOr(settings.getUser("editor.insertSpaces"), defaults.insertSpaces()));
    CheckBox wordWrap = new CheckBox("Wrap long lines");
    wordWrap.setSelected(boolOr(settings.getUser("editor.wordWrap"), defaults.wordWrap()));
    CheckBox lineNumbers = new CheckBox("Show line numbers");
    lineNumbers.setSelected(boolOr(settings.getUser("editor.lineNumbers"), defaults.lineNumbers()));
    CheckBox autoIndent = new CheckBox("Keep indentation on new lines");
    autoIndent.setSelected(boolOr(settings.getUser("editor.autoIndent"), defaults.autoIndent()));

    GridPane editorGrid = grid();
    editorGrid.addRow(0, new Label("Font family"), font);
    editorGrid.addRow(1, new Label("Font size"), fontSize);
    editorGrid.addRow(2, new Label("Tab size"), tabSize);
    editorGrid.add(insertSpaces, 1, 3);
    editorGrid.add(wordWrap, 1, 4);
    editorGrid.add(lineNumbers, 1, 5);
    editorGrid.add(autoIndent, 1, 6);
    Label editorNote =
        new Label(
            "A project's own settings (Settings > Project Settings...) override these for that project.");
    editorNote.setWrapText(true);
    editorGrid.add(editorNote, 0, 7, 2, 1);

    ComboBox<Theme> themeBox = new ComboBox<>(FXCollections.observableArrayList(themes));
    themeBox.setConverter(
        new javafx.util.StringConverter<>() {
          @Override
          public String toString(Theme theme) {
            return theme == null ? "" : theme.name();
          }

          @Override
          public Theme fromString(String text) {
            return null;
          }
        });
    Theme initialTheme =
        themes.stream()
            .filter(t -> t.id().equals(currentThemeId))
            .findFirst()
            .orElse(themes.get(0));
    themeBox.getSelectionModel().select(initialTheme);
    Label themeNote = new Label("Custom themes are .css files in:\n" + settings.themesDirectory());
    themeNote.setWrapText(true);
    GridPane themeGrid = grid();
    themeGrid.addRow(0, new Label("Theme"), themeBox);
    themeGrid.add(themeNote, 0, 1, 2, 1);

    // Keybindings work on a copy and are written only on OK.
    List<Row> rows = keys.actions().stream().map(a -> new Row(a, keys.binding(a.id()))).toList();
    TableView<Row> table = new TableView<>(FXCollections.observableArrayList(rows));
    TableColumn<Row, String> actionColumn = new TableColumn<>("Action");
    actionColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().action.label()));
    actionColumn.setPrefWidth(300);
    TableColumn<Row, String> shortcutColumn = new TableColumn<>("Shortcut");
    shortcutColumn.setCellValueFactory(c -> c.getValue().shown);
    shortcutColumn.setPrefWidth(180);
    table.getColumns().add(actionColumn);
    table.getColumns().add(shortcutColumn);
    table.setPrefHeight(320);
    Button change = new Button("Change...");
    Button unbind = new Button("Unbind");
    Button reset = new Button("Reset");
    Button resetAll = new Button("Reset All");
    change.setOnAction(e -> changeShortcut(owner, table, rows));
    unbind.setOnAction(
        e -> {
          Row row = table.getSelectionModel().getSelectedItem();
          if (row != null) row.set("");
        });
    reset.setOnAction(
        e -> {
          Row row = table.getSelectionModel().getSelectedItem();
          if (row != null) row.set(row.action.defaultBinding());
        });
    resetAll.setOnAction(e -> rows.forEach(r -> r.set(r.action.defaultBinding())));
    table.setOnMouseClicked(
        e -> {
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
            changeShortcut(owner, table, rows);
        });
    for (Button button : List.of(change, unbind, reset))
      button.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
    VBox keysPage = new VBox(8, table, new HBox(8, change, unbind, reset, resetAll));
    keysPage.setPadding(new Insets(10));
    VBox.setVgrow(table, Priority.ALWAYS);

    TabPane pages =
        new TabPane(
            page("Editor", editorGrid),
            page("Appearance", themeGrid),
            page("Keybindings", keysPage));
    pages.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Settings");
    dialog.setHeaderText("Saved to " + settings.userFile());
    dialog.getDialogPane().setContent(pages);
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    if (Dialogs.show(dialog).orElse(ButtonType.CANCEL) != ButtonType.OK) return false;

    // Write a value when it differs from the default or the key is already in the file; otherwise
    // keep the file free of it.
    setUser(settings, "editor.fontFamily", font.getText().strip(), defaults.fontFamily());
    setUser(
        settings,
        "editor.fontSize",
        String.valueOf(fontSize.getValue()),
        String.valueOf(defaults.fontSize()));
    setUser(
        settings,
        "editor.tabSize",
        String.valueOf(tabSize.getValue()),
        String.valueOf(defaults.tabSize()));
    setUser(
        settings,
        "editor.insertSpaces",
        String.valueOf(insertSpaces.isSelected()),
        String.valueOf(defaults.insertSpaces()));
    setUser(
        settings,
        "editor.wordWrap",
        String.valueOf(wordWrap.isSelected()),
        String.valueOf(defaults.wordWrap()));
    setUser(
        settings,
        "editor.lineNumbers",
        String.valueOf(lineNumbers.isSelected()),
        String.valueOf(defaults.lineNumbers()));
    setUser(
        settings,
        "editor.autoIndent",
        String.valueOf(autoIndent.isSelected()),
        String.valueOf(defaults.autoIndent()));
    Theme chosen = themeBox.getSelectionModel().getSelectedItem();
    if (chosen != null && !chosen.equals(initialTheme))
      settings.put(Settings.Scope.USER, "theme", chosen.id());
    java.util.Map<String, String> wanted = new java.util.LinkedHashMap<>();
    for (Row row : rows) wanted.put(row.action.id(), row.binding);
    keys.apply(wanted);
    return save(settings, Settings.Scope.USER);
  }

  // ---- project settings ----

  public static boolean showProject(Window owner, Settings settings) {
    TextField tabSize = new TextField(valueOr(settings.getProject("editor.tabSize"), ""));
    tabSize.setPromptText("inherit");
    ChoiceBox<String> insertSpaces = triState(settings.getProject("editor.insertSpaces"));
    ChoiceBox<String> wordWrap = triState(settings.getProject("editor.wordWrap"));
    ChoiceBox<String> autoIndent = triState(settings.getProject("editor.autoIndent"));
    TextField runCommand = new TextField(valueOr(settings.getProject("run.command"), ""));
    runCommand.setPromptText("detected from the project");
    TextField buildCommand = new TextField(valueOr(settings.getProject("build.command"), ""));
    buildCommand.setPromptText("detected from the project");
    TextField exclude = new TextField(valueOr(settings.getProject("tree.exclude"), ""));
    exclude.setPromptText(".git,target");

    GridPane grid = grid();
    grid.addRow(0, new Label("Tab size"), tabSize);
    grid.addRow(1, new Label("Insert spaces"), insertSpaces);
    grid.addRow(2, new Label("Wrap long lines"), wordWrap);
    grid.addRow(3, new Label("Keep indentation"), autoIndent);
    grid.addRow(4, new Label("Run command"), runCommand);
    grid.addRow(5, new Label("Build command"), buildCommand);
    grid.addRow(6, new Label("Hide from tree"), exclude);
    Label note =
        new Label(
            "Empty or \"Inherit\" means: use the user settings. Language server commands can be set in the file (language.server.<id>).");
    note.setWrapText(true);
    note.setPrefWidth(420);
    grid.add(note, 0, 7, 2, 1);

    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Project Settings");
    dialog.setHeaderText("Saved to " + settings.projectFile());
    dialog.getDialogPane().setContent(grid);
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          String text = tabSize.getText().strip();
          if (text.isEmpty()) return;
          try {
            int value = Integer.parseInt(text);
            if (value < EditorPreferences.MIN_TAB_SIZE || value > EditorPreferences.MAX_TAB_SIZE)
              throw new NumberFormatException();
          } catch (NumberFormatException ex) {
            event.consume();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.initOwner(owner);
            alert.setHeaderText(null);
            alert.setContentText(
                "Tab size must be a whole number from "
                    + EditorPreferences.MIN_TAB_SIZE
                    + " to "
                    + EditorPreferences.MAX_TAB_SIZE
                    + ", or empty.");
            Dialogs.show(alert);
          }
        });
    if (Dialogs.show(dialog).orElse(ButtonType.CANCEL) != ButtonType.OK) return false;

    setProject(settings, "editor.tabSize", tabSize.getText().strip());
    setProject(settings, "editor.insertSpaces", fromTriState(insertSpaces));
    setProject(settings, "editor.wordWrap", fromTriState(wordWrap));
    setProject(settings, "editor.autoIndent", fromTriState(autoIndent));
    setProject(settings, "run.command", runCommand.getText().strip());
    setProject(settings, "build.command", buildCommand.getText().strip());
    // An emptied "hide from tree" field removes the key (inherit); to hide nothing, write
    // tree.exclude= in the file.
    setProject(settings, "tree.exclude", exclude.getText().strip());
    return save(settings, Settings.Scope.PROJECT);
  }

  // ---- keybinding capture ----

  private static void changeShortcut(Window owner, TableView<Row> table, List<Row> rows) {
    Row row = table.getSelectionModel().getSelectedItem();
    if (row == null) return;
    Optional<String> captured = capture(owner, row.action.label());
    if (captured.isEmpty()) return;
    String binding = captured.get();
    String normal = Keybindings.normalize(binding);
    for (Row other : rows) {
      if (other == row || !Keybindings.normalize(other.binding).equals(normal)) continue;
      Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
      confirm.initOwner(owner);
      confirm.setHeaderText(null);
      confirm.setContentText(
          Shortcuts.display(binding)
              + " is already used by \""
              + other.action.label()
              + "\".\nMove it to \""
              + row.action.label()
              + "\"?");
      if (Dialogs.show(confirm).orElse(ButtonType.CANCEL) != ButtonType.OK) return;
      other.set("");
    }
    row.set(binding);
  }

  private static Optional<String> capture(Window owner, String label) {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Change Shortcut");
    dialog.setHeaderText(label + "\nPress the new shortcut, then OK.");
    TextField field = new TextField();
    field.setEditable(false);
    field.setPromptText("Press keys...");
    String[] captured = {null};
    field.addEventFilter(
        KeyEvent.KEY_PRESSED,
        e -> {
          if (e.getCode() == KeyCode.ESCAPE
              && !e.isControlDown()
              && !e.isAltDown()
              && !e.isMetaDown()) return; // let Escape cancel the dialog
          String binding = Shortcuts.fromEvent(e);
          if (binding != null) {
            captured[0] = binding;
            field.setText(Shortcuts.display(binding));
          }
          e.consume();
        });
    dialog.getDialogPane().setContent(field);
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.setDisable(true);
    field.textProperty().addListener((o, a, b) -> ok.setDisable(captured[0] == null));
    dialog.setResultConverter(type -> type == ButtonType.OK ? captured[0] : null);
    Platform.runLater(field::requestFocus);
    return Dialogs.show(dialog);
  }

  // ---- helpers ----

  private static final class Row {
    final Keybindings.Action action;
    String binding;
    final StringProperty shown = new SimpleStringProperty();

    Row(Keybindings.Action action, String binding) {
      this.action = action;
      set(binding);
    }

    void set(String value) {
      binding = value == null ? "" : value;
      shown.set(
          binding.isEmpty()
              ? ""
              : Shortcuts.display(binding).isEmpty()
                  ? binding + "  (not understood)"
                  : Shortcuts.display(binding));
    }
  }

  private static Tab page(String title, Node content) {
    Tab tab = new Tab(title, content);
    tab.setClosable(false);
    return tab;
  }

  private static GridPane grid() {
    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(8);
    grid.setPadding(new Insets(12));
    return grid;
  }

  private static ChoiceBox<String> triState(String value) {
    ChoiceBox<String> box =
        new ChoiceBox<>(FXCollections.observableArrayList("Inherit", "Yes", "No"));
    box.getSelectionModel()
        .select(
            "true".equalsIgnoreCase(value)
                ? "Yes"
                : "false".equalsIgnoreCase(value) ? "No" : "Inherit");
    return box;
  }

  private static String fromTriState(ChoiceBox<String> box) {
    String selected = box.getSelectionModel().getSelectedItem();
    return "Yes".equals(selected) ? "true" : "No".equals(selected) ? "false" : "";
  }

  private static void setUser(Settings settings, String key, String value, String defaultValue) {
    boolean present = settings.getUser(key) != null;
    if (value.isEmpty() || (value.equals(defaultValue) && !present))
      settings.remove(Settings.Scope.USER, key);
    else settings.put(Settings.Scope.USER, key, value);
  }

  /** Empty means "inherit": the key is removed from the project file. */
  private static void setProject(Settings settings, String key, String value) {
    if (value.isEmpty()) settings.remove(Settings.Scope.PROJECT, key);
    else settings.put(Settings.Scope.PROJECT, key, value);
  }

  private static boolean save(Settings settings, Settings.Scope scope) {
    try {
      settings.save(scope);
      return true;
    } catch (IOException ex) {
      Alert alert = new Alert(Alert.AlertType.ERROR);
      alert.setHeaderText(null);
      alert.setContentText("Could not save settings: " + ex.getMessage());
      Dialogs.show(alert);
      return false;
    }
  }

  private static String valueOr(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private static boolean boolOr(String value, boolean fallback) {
    if (value == null) return fallback;
    if (value.strip().equalsIgnoreCase("true")) return true;
    if (value.strip().equalsIgnoreCase("false")) return false;
    return fallback;
  }

  private static int intOr(String value, int fallback, int min, int max) {
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(value.strip())));
    } catch (RuntimeException ex) {
      return fallback;
    }
  }
}
