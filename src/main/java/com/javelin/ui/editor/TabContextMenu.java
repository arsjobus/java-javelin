package com.javelin.ui.editor;

import java.util.List;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;

/** Lets a tab be closed from its header without relying on which tab is currently selected. */
final class TabContextMenu {
    private final TabPane tabs;
    private final Predicate<Tab> confirmClose;
    private ContextMenu tabContextMenu;
    private boolean tabContextMenuOpen;

    TabContextMenu(TabPane tabs, Predicate<Tab> confirmClose) {
        this.tabs = tabs;
        this.confirmClose = confirmClose;
    }

    void install() {
        tabs.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            Node header = event.getTarget() instanceof Node node ? tabHeader(node) : null;
            if (header == null) return;
            Node regionNode = tabs.lookup(".tab-header-area .headers-region");
            if (!(regionNode instanceof Parent region)) return;
            int index = region.getChildrenUnmodifiable().indexOf(header);
            Tab target = TabHeaderTargets.at(tabs.getTabs(), index).orElse(null);
            if (target == null) return;
            MenuItem close = new MenuItem("Close this Tab");
            close.setOnAction(e -> { if (confirmClose.test(target)) tabs.getTabs().remove(target); });
            MenuItem closeOthers = new MenuItem("Close Other Tabs");
            closeOthers.setOnAction(e -> closeOtherTabs(target));
            ContextMenu menu = new ContextMenu(close, closeOthers);
            menu.setConsumeAutoHidingEvents(true);
            tabContextMenu = menu;
            tabContextMenuOpen = true;
            menu.setOnHidden(e -> Platform.runLater(() -> {
                if (tabContextMenu == menu) {
                    tabContextMenu = null;
                    tabContextMenuOpen = false;
                }
            }));
            menu.show(tabs, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    void dismissOnOutsidePress(MouseEvent event) {
        if (!tabContextMenuOpen) return;
        tabContextMenuOpen = false;
        if (tabContextMenu != null && tabContextMenu.isShowing()) tabContextMenu.hide();
        event.consume();
    }

    private void closeOtherTabs(Tab keep) {
        if (!tabs.getTabs().contains(keep)) return;
        List<Tab> others = TabHeaderTargets.others(tabs.getTabs(), keep);
        for (Tab tab : others) {
            if (!confirmClose.test(tab)) {
                tabs.getSelectionModel().select(keep);
                return;
            }
            tabs.getTabs().remove(tab);
        }
        tabs.getSelectionModel().select(keep);
    }

    private Node tabHeader(Node node) {
        for (Node current = node; current != null && current != tabs; current = current.getParent())
            if (current.getStyleClass().contains("tab")) return current;
        return null;
    }
}
