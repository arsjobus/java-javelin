# Customization

javelin IDE is configured with plain text files. There is no settings database, no sync and no network access:
everything below is a file you can read, edit, copy, back up or delete.

## Where settings live

| Layer | File | Notes |
| --- | --- | --- |
| User | Linux: `~/.config/javelin-ide/settings.conf` (or `$XDG_CONFIG_HOME/javelin-ide/`). macOS: `~/Library/Application Support/javelin-ide/settings.conf`. Windows: `%APPDATA%\javelin-ide\settings.conf` | Set `JAVELIN_IDE_CONFIG_DIR` to use another folder. |
| Project | `<project>/.javelin-ide/settings.conf` | Overrides the user layer for that project. Commit it to share it, or ignore the folder. |

Open either file from the **Settings** menu (it is created from a fully commented template on first use). Saving a
settings file inside javelin IDE applies it immediately; **Settings > Reload Settings** does the same on demand.
**Settings > Settings...** and **Project Settings...** are dialogs over the same files.

## File format

UTF-8, one `key=value` per line. Lines starting with `#` are comments. Values are taken exactly as written to the end
of the line, with no escape sequences, so `run.command=C:\tools\run.bat --fast` means what it says. Saving from a
dialog changes only the lines that changed; your comments, ordering and other entries are kept.

Problems (a line without `=`, a number out of range, an unknown action id) are listed in the Output panel and never stop
the editor from starting; the affected value falls back to its default.

## Settings

| Key | Default | Where | Meaning |
| --- | --- | --- | --- |
| `theme` | `light` | user only | `light`, `dark`, `high-contrast`, or a custom theme name (below). |
| `app.icon` | built-in icon | user only | Path to a PNG, JPEG or GIF used for the window, taskbar and macOS Dock icon (`~` is your home folder). A missing or unreadable file falls back to the built-in icon and says so in the status bar. |
| `search.caseSensitive` | `false` | user only | Whether Search matches uppercase and lowercase letters exactly. The Search dialog's Match case checkbox updates this setting. |
| `editor.fontFamily` | `Monospaced` | user, project | Editor font. |
| `editor.fontSize` | `14` | user, project | 8 to 48. |
| `editor.tabSize` | `4` | user, project | 1 to 16. Used for Tab, auto-indent and Format Document. |
| `editor.insertSpaces` | `true` | user, project | Tab inserts spaces up to the next tab stop; `false` inserts a tab character. |
| `editor.wordWrap` | `false` | user, project | Wrap long lines. |
| `editor.lineNumbers` | `true` | user, project | Show the line-number gutter. |
| `editor.autoIndent` | `true` | user, project | Keep indentation on Enter, and indent after an opening bracket. |
| `run.command` | detected | user, project | Pre-filled into Run > Run Command...; you still confirm it. |
| `build.command` | detected | user, project | Replaces the detected command for Run > Build. The command is printed in Output when it runs. |
| `tree.exclude` | `.git,target` | user, project | Comma-separated names hidden from the project tree and Go to File. Write `tree.exclude=` to hide nothing. |
| `language.server.<id>` | suggestion | user, project | Pre-filled into Language > Start Language Server...; servers are still started only after you confirm. |
| `key.<action id>` | see below | user only | Keybinding override. |

`theme`, `key.*` and `search.caseSensitive` are read from the user file only. A project cannot change your theme,
shortcuts or Search behavior by being opened;
if a project file contains them they are ignored with a warning.

`build.command`, `run.command` and `language.server.*` in a project file are commands that came with the project. Treat
them like the project's own build scripts: read them before building a project you did not write.

View > Zoom In / Zoom Out / Reset Zoom change the font size for the current session only and are never saved.

## Themes

Built in: Light, Dark and High Contrast (View > Theme). To add your own, put a `.css` file in the `themes` folder next to
the user settings file; its name (without `.css`) becomes the theme name, and you select it from View > Theme or with
`theme=<name>`. Saving a theme file inside javelin IDE re-applies it.

A custom theme is an ordinary JavaFX stylesheet loaded after javelin IDE's own, so it can override anything. Put
`/* base: dark */` near the top to keep the dark theme's rules underneath and only change what you want:

```css
/* base: dark */
.root.theme-custom { -fx-base: #1b2630; -fx-background: #16202a; -fx-control-inner-background: #16202a; }
.theme-custom .code-area { -fx-background-color: #101922; }
.theme-custom .code-area .keyword { -fx-fill: #ffb454; }
```

Style classes you can rely on: `.code-area` with `.keyword`, `.type`, `.string`, `.comment`, `.annotation`, `.number`,
`.lineno`, `.diag-error`, `.diag-warning`; `.problem-error`, `.problem-warning`; `.popup-pane`. See `editor.css`.

## Keybindings

Every menu item is an action with an id. Override one with `key.<id>=<shortcut>` in the user file, or use
Settings > Settings... > Keybindings. Use `none` to remove a shortcut.

A shortcut is modifiers joined by `+`, then one key: `Ctrl+Shift+F`, `Alt+Z`, `F12`. Modifiers are `Ctrl`, `Shift`, `Alt`,
`Meta` and `Shortcut` (Ctrl on Windows/Linux, Cmd on macOS). Key names are JavaFX `KeyCode` names, case-insensitive:
letters, `DIGIT0`..`DIGIT9` (or just `0`..`9`), `F1`..`F12`, `COMMA`, `PERIOD`, `MINUS`, `EQUALS`, `BACK_QUOTE`, `SPACE`,
`ENTER`, `TAB`, and so on. In the dialog, any key other than a function key must be combined with Ctrl, Alt or Meta so a
shortcut cannot swallow normal typing. Two actions with the same shortcut are reported as a problem.

Every default that needs a command key is written with `Shortcut+`, so it is Cmd on macOS and Ctrl on Windows and
Linux: Save is Cmd+S / Ctrl+S, Search is Cmd+F / Ctrl+F, Build is Cmd+B / Ctrl+B, and so on. Function-key defaults (F2,
F3, F5, F12) are the same everywhere. Other File actions have no shortcut by default. To use a different one, for
example Ctrl+S even on macOS:

```
key.file.save=Ctrl+S
key.file.saveAs=Ctrl+Shift+S
```

`Shortcut+S` and `Ctrl+S` count as the same shortcut off macOS (and `Shortcut+S` equals `Cmd+S` on macOS) when
conflicts are checked.

Only menu actions are rebindable. Keys handled inside the text area itself (cursor movement, Ctrl+Z, copy and paste,
Ctrl+A) are not.

| Action id | Menu item | Default |
| --- | --- | --- |
| `file.new` | File > New | (none) |
| `file.openFile` | File > Open File | (none) |
| `file.openDirectory` | File > Open Directory | (none) |
| `file.save` | File > Save | `Shortcut+S` (Cmd+S on macOS) |
| `file.saveAs` | File > Save As | `Shortcut+Shift+S` |
| `file.close` | File > Close | (none) |
| `file.exit` | File > Exit | (none) |
| `edit.undo` | Edit > Undo | (none) |
| `edit.redo` | Edit > Redo | (none) |
| `edit.search` | Edit > Search | `Shortcut+F` |
| `edit.findNext` | Edit > Find Next | `F3` |
| `edit.findPrevious` | Edit > Find Previous | `Shift+F3` |
| `edit.replace` | Edit > Replace | `Shortcut+H` |
| `edit.goToLine` | Edit > Go to Line | `Shortcut+L` |
| `edit.goToFile` | Edit > Go to File | `Shortcut+P` |
| `edit.matchBracket` | Edit > Match Bracket | `Shortcut+M` |
| `edit.addCursor` | Edit > Add Cursor | `Shortcut+D` |
| `view.focusMode` | View > Focus Mode | (none) |
| `view.fold` | View > Fold Selected | (none) |
| `view.unfold` | View > Unfold Current | (none) |
| `view.bottomPanel` | View > Toggle Bottom Panel | `Shortcut+J` |
| `view.output` | View > Output | (none) |
| `view.problems` | View > Problems | (none) |
| `view.references` | View > References | (none) |
| `view.terminal` | View > Terminal | `Shortcut+BACK_QUOTE` |
| `view.newTerminal` | View > New Terminal | `Shortcut+Shift+BACK_QUOTE` |
| `view.zoomIn` | View > Zoom In | `Shortcut+EQUALS` |
| `view.zoomOut` | View > Zoom Out | `Shortcut+MINUS` |
| `view.zoomReset` | View > Reset Zoom | `Shortcut+DIGIT0` |
| `language.startServer` | Language > Start Language Server | (none) |
| `language.stopServer` | Language > Stop Language Server | (none) |
| `language.complete` | Language > Complete | `Shortcut+SPACE` |
| `language.hover` | Language > Show Hover Info | `Shortcut+I` |
| `language.signatureHelp` | Language > Signature Help | `Shortcut+Shift+SPACE` |
| `language.goToDefinition` | Language > Go to Definition | `F12` |
| `language.findReferences` | Language > Find References | `Shift+F12` |
| `language.documentSymbols` | Language > Document Symbols | `Shortcut+Shift+O` |
| `language.rename` | Language > Rename Symbol | `F2` |
| `language.format` | Language > Format Document | `Shortcut+Shift+F` |
| `run.build` | Run > Build | `Shortcut+B` |
| `run.runCommand` | Run > Run Command | `F5` |
| `run.stopAll` | Run > Stop All Processes | `Shortcut+PERIOD` |
| `run.processes` | Run > Processes | (none) |
| `settings.open` | Settings > Settings | `Shortcut+COMMA` |
| `settings.project` | Settings > Project Settings | (none) |
| `settings.openUserFile` | Settings > Open User Settings File | (none) |
| `settings.openProjectFile` | Settings > Open Project Settings File | (none) |
| `settings.reload` | Settings > Reload Settings | (none) |
