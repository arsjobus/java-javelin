package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real dialogs on a real window: who owns them, and that the main window is still on screen after they close. Needs a
 * display, so it is skipped on a machine without one (a headless build server).
 */
class DialogsFxTest {
    private static boolean display;

    @BeforeAll static void startToolkit() {
        try { Platform.startup(() -> {}); display = true; }
        catch (IllegalStateException alreadyRunning) { display = true; }
        catch (RuntimeException | Error noDisplay) { display = false; }
    }

    private static <T> T fx(Callable<T> job) throws Exception {
        FutureTask<T> task = new FutureTask<>(job);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static Dialog<ButtonType> dialog(String title) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        return d;
    }

    private static Stage mainWindow() throws Exception {
        return fx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 300, 200));
            stage.show();
            Dialogs.setOwner(stage);
            return stage;
        });
    }

    @Test void aDialogIsOwnedByTheMainWindowAndTheWindowIsStillThereAfterCancel() throws Exception {
        assumeTrue(display, "needs a display to run JavaFX windows");
        Stage main = mainWindow();
        AtomicReference<Window> owner = new AtomicReference<>();
        fx(() -> {
            Dialog<ButtonType> d = dialog("Find");
            Platform.runLater(() -> { owner.set(d.getOwner()); d.close(); });   // Cancel, from inside the dialog's own event loop
            Dialogs.show(d);
            return null;
        });
        fx(() -> null);   // let the deferred restore run
        assertSame(main, owner.get());
        assertFalse(fx(main::isIconified), "cancelling a dialog must not minimize the main window");
        assertTrue(fx(main::isShowing));
    }

    @Test void theReplaceDialogCanOpenImmediatelyAfterFindCloses() throws Exception {
        assumeTrue(display, "needs a display to run JavaFX windows");
        Stage main = mainWindow();
        AtomicBoolean replaceWasShowing = new AtomicBoolean();
        AtomicReference<Window> replaceOwner = new AtomicReference<>();
        fx(() -> {
            Dialog<ButtonType> find = dialog("Find");
            Dialog<ButtonType> replace = dialog("Replace");
            Platform.runLater(find::close);
            Dialogs.show(find);
            Platform.runLater(() -> {
                replaceWasShowing.set(replace.isShowing());
                replaceOwner.set(replace.getOwner());
                replace.close();
            });
            Dialogs.show(replace);
            return null;
        });
        fx(() -> null);
        assertTrue(replaceWasShowing.get());
        assertSame(main, replaceOwner.get());
        assertFalse(fx(main::isIconified));
    }

    @Test void aDialogOpenedFromADialogIsOwnedByIt_notByTheMainWindow() throws Exception {
        assumeTrue(display, "needs a display to run JavaFX windows");
        Stage main = mainWindow();
        AtomicReference<Window> outerWindow = new AtomicReference<>();
        AtomicReference<Window> innerOwner = new AtomicReference<>();
        fx(() -> {
            Dialog<ButtonType> outer = dialog("Settings");
            Dialog<ButtonType> inner = dialog("Change Shortcut");
            inner.initOwner(main);   // as SettingsDialog does: it hands the main window to everything it opens
            Platform.runLater(() -> {
                outerWindow.set(outer.getDialogPane().getScene().getWindow());
                Platform.runLater(() -> { innerOwner.set(inner.getOwner()); inner.close(); });
                Dialogs.show(inner);
                assertTrue(outer.isShowing(), "closing the inner dialog leaves the outer one open");
                outer.close();
            });
            Dialogs.show(outer);
            return null;
        });
        fx(() -> null);
        assertSame(outerWindow.get(), innerOwner.get(), "a sibling of the Settings dialog would fight it for focus when it closes");
        assertFalse(fx(main::isIconified));
    }
}
