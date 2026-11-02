package com.javelin.ui.project;

import com.javelin.io.ProjectFiles;
import com.javelin.io.ProjectWatcher;
import com.javelin.io.Trash;
import com.javelin.ui.Dialogs;
import com.javelin.ui.TreeGestures;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.shell.EditorListener;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** The project sidebar: the file tree, its context menu, drag and drop, and keeping it in step with the disk. */
public final class ProjectTree implements EditorListener {
    private static final long TREE_WATCH_MILLIS = 2000;

    private final Workbench bench;
    private final EditorTabs editors;
    private final TreeView<File> projectTree = new TreeView<>();
    private final ContextMenu treeMenu = new ContextMenu();
    private MenuItem renameTreeItem;
    private final TreeGestures treeGestures = new TreeGestures();
    // Notices files added, removed, renamed or moved outside the editor (terminal, build, git, file manager).
    private final ProjectWatcher treeWatcher;
    private VBox sidebar;

    public ProjectTree(Workbench bench, EditorTabs editors) {
        this.bench = bench;
        this.editors = editors;
        this.treeWatcher = new ProjectWatcher(bench::treeExcludes, () -> Platform.runLater(this::refreshAfterOutsideChange));
    }

    /** The sidebar to place at the left of the window. */
    public Region sidebar() {
        if (sidebar != null) return sidebar;
        projectTree.setShowRoot(true);
        projectTree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        projectTree.setCellFactory(tree -> new TreeCell<>() {
            {   // right-clicking a row selects it first, so the menu always acts on the row under the pointer
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (event.getButton() != MouseButton.SECONDARY || isEmpty() || getTreeItem() == null) return;
                    if (!projectTree.getSelectionModel().getSelectedItems().contains(getTreeItem()))
                        projectTree.getSelectionModel().clearAndSelect(projectTree.getRow(getTreeItem()));
                    event.consume(); // keep a multi-selection when its selected rows are right-clicked
                });
                setOnContextMenuRequested(event -> {
                    if (!isEmpty() && getTreeItem() != null && !projectTree.getSelectionModel().getSelectedItems().contains(getTreeItem()))
                        projectTree.getSelectionModel().clearAndSelect(projectTree.getRow(getTreeItem()));
                    renameTreeItem.setDisable(projectTree.getSelectionModel().getSelectedItems().size() != 1);
                });
                // Double-click opens a file. It is decided from the presses on this very row, not from the tree's
                // selection or JavaFX's click count, so a shaky hand, a drag or a tree rebuild cannot lose or redirect it.
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (event.getButton() != MouseButton.PRIMARY) return;
                    File row = isEmpty() ? null : getItem();
                    boolean doubleClick = treeGestures.press(row, System.currentTimeMillis(), event.getScreenX(), event.getScreenY());
                    if (doubleClick && row.isFile() && getTreeItem() != null) {
                        projectTree.getSelectionModel().select(getTreeItem());
                        editors.openFile(row);
                    }
                });
                // Drag a row onto a folder (or onto a file, meaning the folder it is in) to move it there.
                setOnDragDetected(event -> {
                    File file = getItem();
                    if (isEmpty() || file == null || file.equals(bench.projectDirectory())) return;
                    // A click with a slightly moving pointer is not a drag; starting one would swallow the click.
                    if (!treeGestures.dragIntended(event.getScreenX(), event.getScreenY())) return;
                    Dragboard board = startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent(); content.putString(file.getAbsolutePath());
                    board.setContent(content); event.consume();
                });
                setOnDragOver(event -> { if (dropTarget(event.getDragboard(), getItem()) != null) event.acceptTransferModes(TransferMode.MOVE); event.consume(); });
                setOnDragDropped(event -> {
                    File source = dragSource(event.getDragboard());
                    File folder = dropTarget(event.getDragboard(), getItem());
                    boolean done = source != null && folder != null && moveInTree(source, folder);
                    event.setDropCompleted(done); event.consume();
                });
            }
            @Override protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getName());
                setContextMenu(empty || file == null ? null : treeMenu);
            }
        });
        MenuItem newFile = new MenuItem("New File..."), newFolder = new MenuItem("New Folder..."), rename = new MenuItem("Rename..."), delete = new MenuItem("Delete");
        renameTreeItem = rename;
        newFile.setOnAction(e -> createInTree(false));
        newFolder.setOnAction(e -> createInTree(true));
        rename.setOnAction(e -> renameInTree());
        delete.setOnAction(e -> deleteFromTree());
        treeMenu.getItems().addAll(newFile, newFolder, new SeparatorMenuItem(), rename, delete);
        // Right-clicking the empty part of the sidebar adds to the project folder itself.
        MenuItem rootFile = new MenuItem("New File..."), rootFolder = new MenuItem("New Folder...");
        rootFile.setOnAction(e -> { projectTree.getSelectionModel().clearSelection(); createInTree(false); });
        rootFolder.setOnAction(e -> { projectTree.getSelectionModel().clearSelection(); createInTree(true); });
        projectTree.setContextMenu(new ContextMenu(rootFile, rootFolder));
        projectTree.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.F2) { renameInTree(); event.consume(); return; } if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE && event.isShortcutDown()) { deleteFromTree(); event.consume(); } });

        Label projectLabel = new Label("PROJECT");
        projectLabel.setStyle("-fx-font-weight: bold;");
        sidebar = new VBox(8, projectLabel, projectTree);
        sidebar.setPadding(new Insets(10));
        sidebar.setPrefWidth(250);
        VBox.setVgrow(projectTree, Priority.ALWAYS);
        return sidebar;
    }

    /** View > Focus Mode: hides or shows the sidebar. */
    public void toggleVisible() {
        Node node = projectTree.getParent();
        if (node != null) { boolean visible = node.isVisible(); node.setVisible(!visible); node.setManaged(!visible); }
    }

    public void startWatching() { treeWatcher.start(TREE_WATCH_MILLIS); }

    public void dispose() { treeWatcher.close(); }

    /** A saved file inside the project can be new, so the tree is rebuilt. */
    @Override public void saved(File file, File previous, String text) {
        File project = bench.projectDirectory();
        if (project != null && file.toPath().startsWith(project.toPath())) refresh();
    }

    public void refresh() {
        File projectDirectory = bench.projectDirectory();
        if (projectDirectory == null) return;
        Set<File> expanded = new HashSet<>();
        collectExpanded(projectTree.getRoot(), expanded);
        List<File> selectedFiles = projectTree.getSelectionModel().getSelectedItems().stream().map(TreeItem::getValue).toList();
        projectTree.setRoot(createTreeItem(projectDirectory));
        restoreExpanded(projectTree.getRoot(), expanded);
        projectTree.getRoot().setExpanded(true);
        // A rebuild must not drop the selection, or the next click/Enter/F2 acts on nothing or on another row.
        for (File selectedFile : selectedFiles) {
            TreeItem<File> again = findTreeItem(projectTree.getRoot(), selectedFile);
            if (again != null) projectTree.getSelectionModel().select(again);
        }
        treeGestures.reset();
        treeWatcher.watch(projectDirectory.toPath()); // what the tree now shows is the baseline, so our own changes are not reported again
    }

    private void refreshAfterOutsideChange() {
        refresh();
        editors.reloadChangedFiles();
    }

    /** Rebuilding the tree must not fold every folder the person had open. */
    private static void collectExpanded(TreeItem<File> item, Set<File> into) {
        if (item == null) return;
        if (item.isExpanded()) into.add(item.getValue());
        for (TreeItem<File> child : item.getChildren()) collectExpanded(child, into);
    }

    private static void restoreExpanded(TreeItem<File> item, Set<File> expanded) {
        if (expanded.contains(item.getValue())) item.setExpanded(true);
        for (TreeItem<File> child : item.getChildren()) restoreExpanded(child, expanded);
    }

    private static TreeItem<File> findTreeItem(TreeItem<File> item, File file) {
        if (item == null) return null;
        if (item.getValue().equals(file)) return item;
        if (!ProjectFiles.isInside(item.getValue().toPath(), file.toPath())) return null;
        for (TreeItem<File> child : item.getChildren()) { TreeItem<File> found = findTreeItem(child, file); if (found != null) return found; }
        return null;
    }

    private TreeItem<File> createTreeItem(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                List<File> sorted = new ArrayList<>(List.of(children));
                sorted.sort(Comparator.comparing(File::isFile).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File child : sorted) if (!bench.treeExcludes().contains(child.getName())) item.getChildren().add(createTreeItem(child));
            }
        }
        return item;
    }

    /** Expands the folders above {@code file}, selects it and scrolls it into view. */
    private void revealInTree(File file) {
        TreeItem<File> item = findTreeItem(projectTree.getRoot(), file);
        if (item == null) return;
        for (TreeItem<File> parent = item.getParent(); parent != null; parent = parent.getParent()) parent.setExpanded(true);
        projectTree.getSelectionModel().select(item);
        projectTree.scrollTo(projectTree.getRow(item));
    }

    /** New file or folder: inside the folder that was right-clicked, beside the file that was, or in the project folder. */
    private void createInTree(boolean folder) {
        String what = folder ? "folder" : "file";
        if (!bench.requireProject("New " + what)) return;
        File projectDirectory = bench.projectDirectory();
        TreeItem<File> selected = projectTree.getSelectionModel().getSelectedItem();
        Path directory = ProjectFiles.directoryFor((selected == null ? projectDirectory : selected.getValue()).toPath());
        String name = "";
        while (true) {
            TextInputDialog dialog = new TextInputDialog(name);
            dialog.setTitle("New " + what);
            dialog.setHeaderText("New " + what + " in " + (directory.equals(projectDirectory.toPath()) ? projectDirectory.getName() : bench.displayPath(directory.toFile())));
            dialog.setContentText("Name:");
            Optional<String> answer = Dialogs.show(dialog);
            if (answer.isEmpty()) return;
            name = answer.get();
            try {
                Path created = folder ? ProjectFiles.createFolder(directory, name) : ProjectFiles.createFile(directory, name);
                refresh();
                revealInTree(created.toFile());
                if (!folder) editors.openFile(created.toFile());
                bench.status("Created " + what + ": " + created);
                return;
            } catch (IOException ex) {
                bench.showError("Could not create " + what, ex.getMessage()); // then ask again, keeping what was typed
            }
        }
    }

    /** Moves the selected files and folders straight to the recycle bin, without asking. Nothing is deleted for good. */
    private void deleteFromTree() {
        if (!bench.requireProject("Delete")) return;
        File projectDirectory = bench.projectDirectory();
        List<TreeItem<File>> selected = new ArrayList<>(projectTree.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) { bench.status("Select files or folders to delete first."); return; }
        if (selected.stream().anyMatch(item -> item.getValue().equals(projectDirectory))) { bench.status("The project folder itself cannot be deleted from here."); return; }
        List<Path> targets = ProjectFiles.topLevelTargets(selected.stream().map(item -> item.getValue().toPath()).toList());
        List<Tab> open = editors.tabsInside(targets);
        String bin = Trash.name();
        List<String> failures = new ArrayList<>();
        Trash.Bin recycleBin = Trash.system();
        for (Path path : targets) try {
            ProjectFiles.trash(projectDirectory.toPath(), path, recycleBin);
        } catch (IOException ex) {
            failures.add(path.getFileName() + ": " + ex.getMessage());
        }
        editors.closeTabsWhoseFileIsGone(open);
        refresh();
        if (failures.isEmpty()) bench.status("Moved " + targets.size() + (targets.size() == 1 ? " item" : " items") + " to the " + bin + ".");
        else bench.showError("Could not move everything to the " + bin, String.join("\n", failures));
    }

    /** The row that was dragged, if the drag carries the path of something inside the project. */
    private File dragSource(Dragboard board) {
        File projectDirectory = bench.projectDirectory();
        if (projectDirectory == null || !board.hasString()) return null;
        File file = new File(board.getString());
        return ProjectFiles.isInside(projectDirectory.toPath(), file.toPath()) && !file.toPath().toAbsolutePath().normalize().equals(projectDirectory.toPath().toAbsolutePath().normalize()) ? file : null;
    }

    /** The folder a drop on {@code row} would move into, or {@code null} if dropping there makes no sense. */
    private File dropTarget(Dragboard board, File row) {
        File source = dragSource(board);
        if (source == null || row == null) return null;
        File folder = ProjectFiles.directoryFor(row.toPath()).toFile();
        return source.isDirectory() && ProjectFiles.isInside(source.toPath(), folder.toPath()) ? null : folder;
    }

    /** Rename the selected file or folder. Open tabs follow it; nothing is ever overwritten. */
    private void renameInTree() {
        if (!bench.requireProject("Rename")) return;
        File projectDirectory = bench.projectDirectory();
        if (projectTree.getSelectionModel().getSelectedItems().size() > 1) { bench.status("Select one file or folder to rename."); return; }
        TreeItem<File> selected = projectTree.getSelectionModel().getSelectedItem();
        if (selected == null) { bench.status("Select a file or folder to rename first."); return; }
        File target = selected.getValue();
        if (target.equals(projectDirectory)) { bench.status("The project folder itself cannot be renamed from here."); return; }
        String what = target.isDirectory() ? "folder" : "file";
        String name = target.getName();
        while (true) {
            TextInputDialog dialog = new TextInputDialog(name);
            dialog.setTitle("Rename " + what);
            dialog.setHeaderText("Rename " + what + " \"" + target.getName() + "\"");
            dialog.setContentText("New name:");
            Optional<String> answer = Dialogs.show(dialog);
            if (answer.isEmpty()) return;
            name = answer.get();
            try {
                Path renamed = ProjectFiles.rename(projectDirectory.toPath(), target.toPath(), name);
                afterMove(target.toPath(), renamed);
                bench.status(renamed.equals(target.toPath().toAbsolutePath().normalize()) ? "Name unchanged" : "Renamed to: " + bench.displayPath(renamed.toFile()));
                return;
            } catch (IOException ex) {
                bench.showError("Could not rename " + what, ex.getMessage()); // then ask again, keeping what was typed
            }
        }
    }

    /** Moves {@code source} into {@code folder} (drag and drop). Returns whether something moved. */
    private boolean moveInTree(File source, File folder) {
        try {
            Path moved = ProjectFiles.moveInto(bench.projectDirectory().toPath(), source.toPath(), folder.toPath());
            if (moved.equals(source.toPath().toAbsolutePath().normalize())) return false; // dropped where it already is
            afterMove(source.toPath(), moved);
            bench.status("Moved to: " + bench.displayPath(moved.toFile()));
            return true;
        } catch (IOException ex) {
            bench.showError("Could not move", ex.getMessage());
            return false;
        }
    }

    /** After {@code from} became {@code to}: repoint every open tab inside it, then redraw the tree. */
    private void afterMove(Path from, Path to) {
        editors.relocate(from, to);
        refresh();
        revealInTree(to.toFile());
    }
}
