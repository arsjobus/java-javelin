package com.javelin.ui.recovery;

import com.javelin.Branding;
import com.javelin.io.RecoveryStore;
import com.javelin.ui.Dialogs;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.shell.EditorListener;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

/** Crash recovery: unsaved buffers are copied to the user's own disk every few seconds and removed once saved or discarded. */
public final class RecoveryController implements EditorListener {
    private static final int AUTOSAVE_SECONDS = 5;
    private final Workbench bench;
    private final EditorTabs editors;
    private final RecoveryStore recovery;
    private final Map<Tab, String> recoveryIds = new HashMap<>();
    private javafx.animation.Timeline autosave;

    public RecoveryController(Workbench bench, EditorTabs editors) {
        this.bench = bench;
        this.editors = editors;
        this.recovery = new RecoveryStore(bench.settings().userFile().getParent().resolve("recovery"));
    }

    public void start() {
        // Other Javelin IDE instances (one per project) share the recovery folder: claim our own snapshots first, so
        // their unsaved work is never offered to this window and ours is never offered to them.
        try { recovery.startSession(); } catch (IOException e) { bench.status("Crash recovery is not shared safely with other windows: " + e.getMessage()); }
        bench.background().execute(() -> recovery.purgeOlderThan(java.time.Duration.ofDays(30)));
        autosave = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(AUTOSAVE_SECONDS), e -> snapshotDirtyTabs()));
        autosave.setCycleCount(javafx.animation.Animation.INDEFINITE);
        autosave.play();
        List<RecoveryStore.Snapshot> leftovers = recovery.pending();
        if (leftovers.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Recover unsaved work");
        alert.setHeaderText(leftovers.size() + (leftovers.size() == 1 ? " document was" : " documents were") + " not saved when " + Branding.FULL_NAME + " last closed unexpectedly.");
        alert.setContentText("Restore " + (leftovers.size() == 1 ? "it" : "them") + " into editor tabs? Choosing Discard deletes the recovered copies.");
        ButtonType restore = new ButtonType("Restore"), discard = new ButtonType("Discard");
        alert.getButtonTypes().setAll(restore, discard);
        if (Dialogs.show(alert).orElse(discard) != restore) { recovery.clear(); return; }
        for (RecoveryStore.Snapshot snapshot : leftovers.stream().sorted(RecoveryStore.TAB_ORDER).toList()) restoreSnapshot(snapshot); // tabs come back in their old order
        bench.status("Restored " + leftovers.size() + (leftovers.size() == 1 ? " unsaved document" : " unsaved documents"));
    }

    /** Stops the periodic copy; called when the window closes. */
    public void stopAutosave() { if (autosave != null) autosave.stop(); }

    private void restoreSnapshot(RecoveryStore.Snapshot snapshot) {
        File original = snapshot.original() == null ? null : snapshot.original().toFile();
        Tab tab = editors.addRecovered(original, snapshot.text(), snapshot.caret());
        recoveryIds.put(tab, snapshot.id()); // keep the same snapshot file, so it is replaced rather than duplicated
        if (original != null && original.isFile() && original.lastModified() > snapshot.savedAt().toEpochMilli())
            bench.status(original.getName() + " changed on disk after this copy was made; check before saving over it.");
        editors.showRecovered(tab);
    }

    private void snapshotDirtyTabs() {
        for (EditorTabs.DirtyBuffer buffer : editors.dirtyBuffers()) {
            String id = recoveryIds.computeIfAbsent(buffer.tab(), t -> recovery.newSessionId());
            File file = buffer.file();
            String text = buffer.text();
            int caret = buffer.caret(), tabIndex = buffer.tabIndex();
            bench.background().execute(() -> { try { recovery.saveIfChanged(id, file == null ? null : file.toPath(), text, caret, tabIndex); } catch (IOException ignored) { /* retried on the next tick */ } });
        }
    }

    /** Saved, discarded or closed: the copy is no longer needed. */
    @Override public void settled(Tab tab) {
        String id = recoveryIds.remove(tab);
        if (id != null) bench.background().execute(() -> recovery.discard(id));
    }
}
