package com.javelin.ui.editor;

import com.javelin.editor.LineNavigation;
import com.javelin.editor.SyntaxHighlighter;
import com.javelin.io.FileIO;
import com.javelin.io.ImageFiles;
import com.javelin.io.ProjectChange;
import com.javelin.io.ProjectFiles;
import com.javelin.language.DiagnosticStore;
import com.javelin.language.Lsp;
import com.javelin.ui.Dialogs;
import com.javelin.ui.ImageViewerPane;
import com.javelin.ui.shell.EditorListener;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;
import org.reactfx.Subscription;

/**
 * The editor tabs: opening, saving, closing and tracking unsaved changes. Everything else that
 * cares about documents (language servers, crash recovery, the project tree, settings files)
 * listens through {@link EditorListener}.
 */
public final class EditorTabs {
  /**
   * Tab property holding the file name Save As should offer for a document that was created with a
   * name in mind.
   */
  private static final String SUGGESTED_NAME = "suggestedName";

  /** An unsaved text buffer, as crash recovery needs to see it. */
  public record DirtyBuffer(Tab tab, File file, String text, int caret, int tabIndex) {}

  private final Workbench bench;
  private final TabPane tabs = new TabPane();
  private final Map<Tab, Boolean> dirty = new HashMap<>();
  private final Map<Tab, Subscription> subscriptions = new HashMap<>();
  private final List<EditorListener> listeners = new ArrayList<>();
  private final DiagnosticStore diagnostics;
  private final EditorFactory factory;
  private final SyntaxStyler styler;
  private final TabContextMenu contextMenu;

  public EditorTabs(Workbench bench, DiagnosticStore diagnostics) {
    this.bench = bench;
    this.diagnostics = diagnostics;
    this.factory = new EditorFactory(bench);
    this.styler = new SyntaxStyler(diagnostics, this::fileOf);
    this.contextMenu = new TabContextMenu(tabs, this::confirmClose);
    tabs.getTabs()
        .addListener(
            (javafx.collections.ListChangeListener<Tab>)
                change -> {
                  while (change.next())
                    if (change.wasRemoved()) change.getRemoved().forEach(this::forgetTab);
                });
    contextMenu.install();
  }

  /** The tab pane to place in the window. */
  public TabPane node() {
    return tabs;
  }

  public void addListener(EditorListener listener) {
    listeners.add(listener);
  }

  /**
   * Lets the tab context menu close itself when the pointer goes down anywhere else in the scene.
   */
  public void attachTo(Scene scene) {
    scene.addEventFilter(
        javafx.scene.input.MouseEvent.MOUSE_PRESSED, contextMenu::dismissOnOutsidePress);
  }

  // ---- looking things up ----

  public Optional<CodeArea> currentEditor() {
    Tab tab = tabs.getSelectionModel().getSelectedItem();
    if (tab != null
        && tab.getContent() instanceof VirtualizedScrollPane<?> pane
        && pane.getContent() instanceof CodeArea a) return Optional.of(a);
    return Optional.empty();
  }

  public File fileOf(CodeArea editor) {
    for (Tab tab : tabs.getTabs())
      if (tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() == editor)
        return (File) tab.getUserData();
    return null;
  }

  public CodeArea editorFor(File file) {
    for (Tab tab : tabs.getTabs())
      if (file.equals(tab.getUserData())
          && tab.getContent() instanceof VirtualizedScrollPane<?> pane
          && pane.getContent() instanceof CodeArea a) return a;
    return null;
  }

  public void forEachEditor(Consumer<CodeArea> action) {
    for (Tab tab : tabs.getTabs())
      if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
          && pane.getContent() instanceof CodeArea a) action.accept(a);
  }

  public long unsavedCount() {
    return tabs.getTabs().stream().filter(this::isDirty).count();
  }

  private boolean isDirty(Tab tab) {
    return Boolean.TRUE.equals(dirty.get(tab));
  }

  /** Unsaved text buffers with the tab each one lives in. */
  public List<DirtyBuffer> dirtyBuffers() {
    List<DirtyBuffer> out = new ArrayList<>();
    for (Tab tab : tabs.getTabs()) {
      if (!isDirty(tab)) continue;
      if (!(tab.getContent() instanceof VirtualizedScrollPane<?> pane)
          || !(pane.getContent() instanceof CodeArea editor)) continue;
      out.add(
          new DirtyBuffer(
              tab,
              (File) tab.getUserData(),
              editor.getText(),
              editor.getCaretPosition(),
              tabs.getTabs().indexOf(tab)));
    }
    return out;
  }

  // ---- styling and preferences ----

  public void highlight(CodeArea editor) {
    styler.highlight(editor);
  }

  public void applyPreferences() {
    forEachEditor(factory::applyPreferences);
  }

  /**
   * Changes the editors' font size for this session only (0 resets); the saved font size is not
   * touched.
   */
  public void zoom(int delta) {
    bench.zoom(delta == 0 ? 0 : Math.max(-30, Math.min(30, bench.zoom() + delta)));
    applyPreferences();
    int zoom = bench.zoom();
    bench.status(
        zoom == 0
            ? "Font size: " + bench.editorPrefs().fontSize()
            : "Font size: "
                + bench.editorPrefs().fontSize()
                + (zoom > 0 ? " +" : " ")
                + zoom
                + " (this session)");
  }

  public void addCursor() {
    currentEditor().ifPresent(factory::addCursor);
  }

  // ---- opening ----

  public void chooseAndOpenFile(Window owner) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Open File");
    File file = Dialogs.guarded(() -> chooser.showOpenDialog(owner));
    if (file != null) openFile(file);
  }

  public void openFile(File file) {
    for (Tab tab : tabs.getTabs())
      if (file.equals(tab.getUserData())) {
        tabs.getSelectionModel().select(tab);
        return;
      }
    if (ImageFiles.isImage(file.toPath())) {
      openImage(file);
      return;
    }
    try {
      CodeArea editor = factory.create();
      editor.replaceText(FileIO.readText(file.toPath()));
      Tab tab = createTab(file.getName(), editor, file);
      tabs.getTabs().add(tab);
      tabs.getSelectionModel().select(tab);
      dirty.put(tab, false);
      fireOpened(file, editor.getText());
      highlight(editor);
      showFromTop(editor);
      bench.status(
          (SyntaxHighlighter.tooLarge(editor.getLength())
                  ? "Opened (large file, syntax highlighting is off): "
                  : "Opened: ")
              + file.getAbsolutePath());
    } catch (IOException ex) {
      bench.showError("Could not open file", ex.getMessage());
    }
  }

  /** Opens {@code file} and puts the caret on the nearest valid line and column. */
  public void openFile(File file, int line, int column) {
    openFile(file);
    Tab tab = tabs.getSelectionModel().getSelectedItem();
    if (tab == null || !file.equals(tab.getUserData())) return;
    currentEditor()
        .ifPresent(
            a -> {
              LineNavigation.Position target =
                  LineNavigation.clamp(
                      line, column, a.getParagraphs().size(), a::getParagraphLength);
              a.moveTo(target.paragraph(), target.column());
              a.requestFollowCaret();
              a.requestFocus();
            });
  }

  /**
   * Makes a freshly opened file start at its first line. Replacing the text leaves the caret at the
   * end, and the editor scrolls to the caret when it is first laid out, so a file would open at the
   * bottom; this puts the caret and the viewport back at the top now and once more after the layout
   * pass.
   */
  private static void showFromTop(CodeArea editor) {
    Runnable top =
        () -> {
          editor.moveTo(0);
          editor.scrollXToPixel(0);
          editor.scrollYToPixel(0);
        };
    top.run();
    Platform.runLater(top);
  }

  /**
   * Pictures open in a read-only viewer tab: no text buffer, no language server, nothing to save.
   */
  private void openImage(File file) {
    try {
      Tab tab = new Tab(file.getName(), ImageViewerPane.load(file));
      tab.setUserData(file);
      tab.setOnCloseRequest(
          e -> {
            if (!confirmClose(tab)) e.consume();
          });
      tabs.getTabs().add(tab);
      tabs.getSelectionModel().select(tab);
      bench.status("Opened image (view only): " + file.getAbsolutePath());
    } catch (IOException ex) {
      bench.showError("Could not open image", ex.getMessage());
    }
  }

  public void newDocument() {
    CodeArea editor = factory.create();
    Tab tab = createTab("Untitled", editor, null);
    tabs.getTabs().add(tab);
    tabs.getSelectionModel().select(tab);
    dirty.put(tab, false);
    bench.status("New document");
  }

  /**
   * Opens an unsaved tab holding {@code text} (generated tests, for one). Save As offers {@code
   * suggestedName}; the tab is marked changed, so closing it asks first, and the work-recovery copy
   * covers it like any unsaved document.
   */
  public void newDocument(String text, String suggestedName) {
    CodeArea editor = factory.create();
    editor.replaceText(text);
    editor.moveTo(0);
    Tab tab = createTab("Untitled", editor, null);
    if (suggestedName != null) tab.getProperties().put(SUGGESTED_NAME, suggestedName);
    tabs.getTabs().add(tab);
    tabs.getSelectionModel().select(tab);
    dirty.put(tab, true);
    updateTabTitle(tab);
    bench.status("New document: " + (suggestedName == null ? "Untitled" : suggestedName));
  }

  /**
   * Brings back an unsaved document after a crash: a dirty tab with the recovered text and caret.
   */
  public Tab addRecovered(File original, String text, int caret) {
    CodeArea editor = factory.create();
    editor.replaceText(text);
    editor.moveTo(caret);
    Tab tab = createTab(original == null ? "Untitled" : original.getName(), editor, original);
    tabs.getTabs().add(tab);
    dirty.put(tab, true);
    updateTabTitle(tab);
    return tab;
  }

  /** Selects a recovered tab, colours it and scrolls to where the cursor was. */
  public void showRecovered(Tab tab) {
    tabs.getSelectionModel().select(tab);
    if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
        && pane.getContent() instanceof CodeArea editor) {
      highlight(editor);
      editor.requestFollowCaret(); // scroll to where the cursor was
    }
  }

  private Tab createTab(String title, CodeArea editor, File file) {
    Tab tab = new Tab(title, new VirtualizedScrollPane<>(editor));
    tab.setUserData(file);
    subscriptions.put(
        tab,
        editor
            .plainTextChanges()
            .subscribe(
                c -> {
                  dirty.put(tab, true);
                  updateTabTitle(tab);
                  File current = (File) tab.getUserData();
                  if (current != null)
                    listeners.forEach(
                        l ->
                            l.changed(
                                current,
                                c.getPosition(),
                                c.getRemoved().length(),
                                c.getInserted()));
                  if (current != null)
                    shiftDiagnostics(
                        editor, current, c.getPosition(), c.getRemoved(), c.getInserted());
                  highlight(editor);
                }));
    tab.setOnCloseRequest(
        e -> {
          if (!confirmClose(tab)) e.consume();
        });
    return tab;
  }

  /**
   * Moves the stored language diagnostics along with an edit, so underlines stay on their text
   * until the server republishes.
   */
  private void shiftDiagnostics(
      CodeArea editor, File file, int offset, String removed, String inserted) {
    if (diagnostics.get(file) == null) return;
    TwoDimensional.Position at =
        editor.offsetToPosition(
            offset, TwoDimensional.Bias.Forward); // text before the edit is unchanged
    diagnostics.shift(file, new Lsp.Position(at.getMajor(), at.getMinor()), removed, inserted);
  }

  public void updateTabTitle(Tab tab) {
    File f = (File) tab.getUserData();
    String title = f == null ? "Untitled" : f.getName();
    tab.setText(isDirty(tab) ? "* " + title : title);
  }

  // ---- saving and closing ----

  public void saveCurrent(boolean forceChoose) {
    Tab tab = tabs.getSelectionModel().getSelectedItem();
    if (tab == null) return;
    if (tab.getContent() instanceof ImageViewerPane) {
      bench.status("Images are view only; there is nothing to save.");
      return;
    }
    File file = (File) tab.getUserData();
    File previous = file;
    if (file == null || forceChoose) {
      FileChooser chooser = new FileChooser();
      chooser.setTitle("Save File");
      if (file == null && tab.getProperties().get(SUGGESTED_NAME) instanceof String suggested)
        chooser.setInitialFileName(suggested);
      file = Dialogs.guarded(() -> chooser.showSaveDialog(tabs.getScene().getWindow()));
      if (file == null) return;
    }
    CodeArea editor = currentEditor().orElse(null);
    if (editor == null) return;
    try {
      FileIO.writeAtomic(file.toPath(), editor.getText());
      tab.setUserData(file);
      dirty.put(tab, false);
      updateTabTitle(tab);
      listeners.forEach(l -> l.settled(tab));
      if (!file.equals(previous))
        highlight(editor); // a new name can change whether the file is highlighted
      bench.status("Saved: " + file.getAbsolutePath());
      if (previous != null && !previous.equals(file)) {
        File old = previous;
        listeners.forEach(l -> l.closed(old));
      }
      File saved = file;
      listeners.forEach(l -> l.saved(saved, previous, editor.getText()));
    } catch (IOException ex) {
      bench.showError("Could not save file", ex.getMessage());
    }
  }

  public void closeCurrentTab() {
    Tab tab = tabs.getSelectionModel().getSelectedItem();
    if (tab != null && confirmClose(tab)) tabs.getTabs().remove(tab);
  }

  private boolean confirmClose(Tab tab) {
    if (!isDirty(tab)) return true;
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    alert.setTitle("Unsaved Changes");
    alert.setHeaderText("Save changes to " + tab.getText().replaceFirst("^\\* ", "") + "?");
    ButtonType save = new ButtonType("Save"),
        discard = new ButtonType("Discard"),
        cancel = ButtonType.CANCEL;
    alert.getButtonTypes().setAll(save, discard, cancel);
    ButtonType result = Dialogs.show(alert).orElse(cancel);
    if (result == save) {
      tabs.getSelectionModel().select(tab);
      saveCurrent(false);
      return !isDirty(tab);
    }
    if (result == discard) {
      listeners.forEach(l -> l.settled(tab));
      return true;
    }
    return false;
  }

  /**
   * Closes every tab, asking Save / Discard / Cancel for each unsaved one. Returns false (leaving
   * the rest open) on Cancel.
   */
  public boolean closeAllTabs() {
    return ProjectChange.closeAllInOrder(
        tabs.getTabs(), this::confirmClose, tab -> tabs.getTabs().remove(tab));
  }

  /** Asks about every unsaved tab (used when the window closes); false when the user cancelled. */
  public boolean confirmCloseDirtyTabs() {
    for (Tab tab : new ArrayList<>(tabs.getTabs())) if (!confirmClose(tab)) return false;
    return true;
  }

  private void forgetTab(Tab tab) {
    Subscription subscription = subscriptions.remove(tab);
    if (subscription != null) subscription.unsubscribe();
    dirty.remove(tab);
    listeners.forEach(l -> l.settled(tab));
    File file = (File) tab.getUserData();
    if (file != null) listeners.forEach(l -> l.closed(file));
  }

  /** Releases everything the tabs hold (text subscriptions) when the window shuts down. */
  public void dispose() {
    subscriptions.values().forEach(Subscription::unsubscribe);
  }

  // ---- reacting to the project changing on disk ----

  /** The open tabs whose file is one of {@code targets} or lies inside one of them. */
  public List<Tab> tabsInside(List<Path> targets) {
    List<Tab> open = new ArrayList<>();
    for (Tab tab : tabs.getTabs())
      if (tab.getUserData() instanceof File f
          && targets.stream().anyMatch(path -> ProjectFiles.isInside(path, f.toPath())))
        open.add(tab);
    return open;
  }

  /**
   * Like any IDE: an open file that was moved to the bin closes with its unsaved text, which is not
   * kept anywhere. Tabs whose file still exists stay open.
   */
  public void closeTabsWhoseFileIsGone(List<Tab> open) {
    for (Tab tab : open)
      if (!Files.exists(((File) tab.getUserData()).toPath())) {
        tabs.getTabs().remove(tab);
        forgetTab(tab);
      }
  }

  /**
   * After {@code from} became {@code to}: repoint every open tab inside it and tell the listeners.
   */
  public void relocate(Path from, Path to) {
    for (Tab tab : new ArrayList<>(tabs.getTabs())) {
      if (!(tab.getUserData() instanceof File old)) continue;
      Path now = ProjectFiles.relocated(from, to, old.toPath());
      if (now == null || now.equals(old.toPath().toAbsolutePath().normalize())) continue;
      listeners.forEach(l -> l.closed(old));
      tab.setUserData(now.toFile());
      updateTabTitle(tab);
      if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
          && pane.getContent() instanceof CodeArea editor) {
        listeners.forEach(l -> l.opened(now.toFile(), editor.getText()));
        highlight(editor); // a new extension can change the language
      }
    }
  }

  /**
   * Refreshes open, clean text buffers when a terminal, Git, or another program changes files on
   * disk.
   */
  public void reloadChangedFiles() {
    int reloaded = 0;
    int conflicts = 0;
    int missing = 0;
    for (Tab tab : tabs.getTabs()) {
      if (!(tab.getUserData() instanceof File file)) continue;
      if (tab.getContent() instanceof ImageViewerPane) continue;
      if (!file.isFile()) {
        missing++;
        continue;
      }
      if (isDirty(tab)) {
        conflicts++;
        continue;
      }
      CodeArea editor = editorFor(file);
      if (editor == null) continue;
      try {
        String diskText = FileIO.readText(file.toPath());
        if (!diskText.equals(editor.getText())) {
          int caret = Math.min(editor.getCaretPosition(), diskText.length());
          editor.replaceText(diskText);
          editor.moveTo(caret);
          dirty.put(tab, false);
          updateTabTitle(tab);
          fireOpened(file, diskText);
          highlight(editor);
          reloaded++;
        }
      } catch (IOException ex) {
        bench.status("Could not reload changed file " + file.getName() + ": " + ex.getMessage());
      }
    }
    if (reloaded > 0)
      bench.status(
          "Reloaded "
              + reloaded
              + (reloaded == 1 ? " changed file from disk" : " changed files from disk"));
    else if (conflicts > 0)
      bench.status(
          "Files changed on disk; kept "
              + conflicts
              + (conflicts == 1 ? " unsaved tab" : " unsaved tabs")
              + " open. Save or reload after reviewing your edits.");
    else if (missing > 0)
      bench.status(
          missing
              + (missing == 1 ? " open file was" : " open files were")
              + " removed from disk; its tab is still open.");
  }

  private void fireOpened(File file, String text) {
    listeners.forEach(l -> l.opened(file, text));
  }
}
