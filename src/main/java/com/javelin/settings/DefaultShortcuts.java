package com.javelin.settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default shortcut of every menu action that has one. JavaFX-free so it can be unit tested.
 *
 * <p>Command keys are written with {@code Shortcut+}, which is Cmd on macOS and Ctrl on
 * Windows/Linux, so no default is tied to Ctrl. Function-key defaults (F3, F12, ...) carry no
 * command key and stay as they are. App registers its menu items with these constants.
 */
public final class DefaultShortcuts {
  private DefaultShortcuts() {}

  public static final String SAVE = Keybindings.SAVE_DEFAULT;
  public static final String SAVE_AS = Keybindings.SAVE_AS_DEFAULT;

  public static final String SEARCH = "Shortcut+F";
  public static final String FIND_NEXT = "F3";
  public static final String FIND_PREVIOUS = "Shift+F3";
  public static final String REPLACE = "Shortcut+H";
  public static final String GO_TO_LINE = "Shortcut+L";
  public static final String GO_TO_FILE = "Shortcut+P";
  public static final String MATCH_BRACKET = "Shortcut+M";
  public static final String ADD_CURSOR = "Shortcut+D";

  public static final String UNDO_DEFAULT = "Shortcut+Z";
  public static final String REDO_DEFAULT = "Shortcut+Y";

  public static final String BOTTOM_PANEL = "Shortcut+J";
  public static final String TERMINAL = "Shortcut+BACK_QUOTE";
  public static final String NEW_TERMINAL = "Shortcut+Shift+BACK_QUOTE";
  public static final String ZOOM_IN = "Shortcut+EQUALS";
  public static final String ZOOM_OUT = "Shortcut+MINUS";
  public static final String ZOOM_RESET = "Shortcut+DIGIT0";

  public static final String COMPLETE = "Shortcut+SPACE";
  public static final String HOVER = "Shortcut+I";
  public static final String SIGNATURE_HELP = "Shortcut+Shift+SPACE";
  public static final String GO_TO_DEFINITION = "F12";
  public static final String FIND_REFERENCES = "Shift+F12";
  public static final String DOCUMENT_SYMBOLS = "Shortcut+Shift+O";
  public static final String RENAME = "F2";
  public static final String FORMAT = "Shortcut+Shift+F";

  public static final String BUILD = "Shortcut+B";
  public static final String RUN_COMMAND = "F5";
  public static final String STOP_ALL = "Shortcut+PERIOD";
  public static final String SETTINGS = "Shortcut+COMMA";

  /** Action id to default shortcut, for every action that has one. */
  public static final Map<String, String> ALL;

  static {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("file.save", SAVE);
    all.put("file.saveAs", SAVE_AS);
    all.put("edit.search", SEARCH);
    all.put("edit.findNext", FIND_NEXT);
    all.put("edit.findPrevious", FIND_PREVIOUS);
    all.put("edit.replace", REPLACE);
    all.put("edit.goToLine", GO_TO_LINE);
    all.put("edit.goToFile", GO_TO_FILE);
    all.put("edit.matchBracket", MATCH_BRACKET);
    all.put("edit.addCursor", ADD_CURSOR);
    all.put("edit.undo", UNDO_DEFAULT);
    all.put("edit.redo", REDO_DEFAULT);
    all.put("view.bottomPanel", BOTTOM_PANEL);
    all.put("view.terminal", TERMINAL);
    all.put("view.newTerminal", NEW_TERMINAL);
    all.put("view.zoomIn", ZOOM_IN);
    all.put("view.zoomOut", ZOOM_OUT);
    all.put("view.zoomReset", ZOOM_RESET);
    all.put("language.complete", COMPLETE);
    all.put("language.hover", HOVER);
    all.put("language.signatureHelp", SIGNATURE_HELP);
    all.put("language.goToDefinition", GO_TO_DEFINITION);
    all.put("language.findReferences", FIND_REFERENCES);
    all.put("language.documentSymbols", DOCUMENT_SYMBOLS);
    all.put("language.rename", RENAME);
    all.put("language.format", FORMAT);
    all.put("run.build", BUILD);
    all.put("run.runCommand", RUN_COMMAND);
    all.put("run.stopAll", STOP_ALL);
    all.put("settings.open", SETTINGS);
    ALL = Collections.unmodifiableMap(all);
  }
}
