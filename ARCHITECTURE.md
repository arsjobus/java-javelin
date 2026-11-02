# Architecture

## Goals

The architecture should be modular without becoming framework-heavy. UI is a client of the editor model rather than the editor model itself.

```text
Application
    |
    +-- UI
    |
    +-- Core
    |    +-- Document model
    |    +-- Commands
    |    +-- Project model
    |    +-- Settings
    |
    +-- Services
         +-- Filesystem
         +-- Search
         +-- Build/Run
         +-- Terminal
         +-- Git
         +-- Language/LSP
         +-- AI
```

## Package layout

```text
com.javelin
├── App.java
├── build
├── core
├── document
├── editor
├── filesystem
├── project
├── search
├── terminal
├── language
├── git
├── ai
├── settings
└── ui
```

The initial release deliberately keeps these packages small. Empty abstractions should not be created merely to make the diagram look complete.

## Technology choices

- Java for the application language.
- JavaFX for the desktop UI.
- Maven for dependency/build management.
- Standard filesystem APIs for ordinary files.
- LSP for language intelligence.
- Tree-sitter may be introduced when syntax parsing needs justify the dependency.
- Git remains Git rather than being replaced by a proprietary project database.

## Dependency rule

Every dependency should answer:
1. What problem does it solve?
2. Can the JDK/JavaFX solve the problem adequately?
3. Does it add meaningful complexity?
4. Does it introduce network, telemetry, account, or lock-in concerns?

Prefer fewer dependencies.

## Language intelligence (v0.5)

`com.javelin.language` is JavaFX-free. `LanguageService` tracks open documents and routes them to user-started servers; `LanguageServerSession` owns one server process and the protocol handshake; `JsonRpc` and `Json` are a small stdio JSON-RPC client written against the JDK, because an LSP client needs very little JSON and the dependency rule asks for a reason to add one. `ui.language.LanguageController` only translates between editor state and this package.

The text and planning logic that used to sit inside `App` now lives here as small static helpers: `TextEdits` (line/character to offset, ordering and applying server edits, the minimal change between two strings), `RenamePlan` (a rename proposal grouped by local file, with the text of the confirmation) and `LanguageProblems` (diagnostics to Problems entries). `ui.language.LanguageController` still decides when to call them and shows the result.

Beyond the v0.5 roadmap items, `LanguageServerSession` also offers signature help, document symbols and whole-document formatting. Each is an explicit menu command under Language; formatting edits are applied to the editor buffer only (unsaved, undoable) and are discarded if the text changed while the server was working.

## Customization (v0.8)

`com.javelin.settings` is JavaFX-free, like `language`. `Settings` layers a user file and a project file over defaults held by the caller; the file format is deliberately trivial (`key=value`, `#` comments, values verbatim) and writes preserve the user's comments and ordering. `Keybindings` is a registry of action ids and shortcut strings, `EditorPreferences` is the validated `editor.*` view of the settings, and `Themes` discovers built-in and custom themes. `ui.shell.KeyBinder` registers each menu item with `Keybindings`, and `ui.Shortcuts` is the only place shortcut strings become JavaFX key combinations. `ui.SettingsDialog` is a thin editor over the same files.

Two rules keep customization from becoming ambient behavior: `theme` and `key.*` are read from the user file only (a project cannot restyle or rebind the editor by being opened), and project commands (`run.command`, `build.command`, `language.server.*`) are either pre-filled into a confirmation dialog or echoed in the Output panel when used. The `java.util.prefs` store still holds recent projects and last-used commands. See `CUSTOMIZATION.md`.

## Layers and the window (v1.0)

Three layers; dependencies only point inward, and `ArchitectureTest` fails the build when one points outward.

```text
Bootstrap (App, Launcher)  ->  ui (JavaFX, one package per feature)  ->  core (JavaFX-free services)
```

- **Core** is `build`, `editor`, `io`, `language`, `launch`, `settings`, `terminal` and `icon`. It never imports JavaFX,
  RichTextFX, anything under `ui`, or `App`, so it is unit tested without a display. Its packages have no cycles
  (`language` and `terminal` use `build`, `settings` uses `io`). `Branding` is the one shared constant every layer may use.
- **ui** is everything with a JavaFX type. The loose widgets in `ui/` (panels, dialogs, popups) are plain building
  blocks. Each feature has its own package:

  | Package | Owns |
  | --- | --- |
  | `ui.shell` | what a window shares: `Workbench` (status line, settings, open project), `WorkArea` (sidebar and editors side by side, draggable), `BottomPanel` (editors and terminal, draggable), `KeyBinder`, and `EditorListener` |
  | `ui.editor` | the document model of the window: `EditorTabs` (open, save, close, unsaved state), `SearchController`, syntax styling, key handling |
  | `ui.project` | `ProjectTree` (the sidebar) and `ProjectSession` (opening a project, recent projects, one window per folder) |
  | `ui.language` | `LanguageController`: every Language menu command and the servers' answers |
  | `ui.run` | `RunController`: build, run, processes |
  | `ui.customize` | `SettingsController` and `ThemeController` |
  | `ui.recovery` | `RecoveryController`: crash recovery |
  | `ui.menu` | `MainMenu`: labels, ids, default shortcuts, and which controller method each item calls |

  Inside `ui`, `shell` knows no feature; `editor` knows only `shell`; `language`, `run`, `recovery` and `customize` know
  only `shell` and `editor`; `project` also knows `customize`; only `App` uses `menu`. The editor tells the rest of the
  window what happened to a document through `EditorListener` (opened, changed, saved, closed, settled), so the language
  servers, the project tree, settings and crash recovery react to documents without the editor knowing any of them.
- **Bootstrap** is `App` (about 130 lines) and `Launcher`. `App` creates each feature, connects them (which listener
  hears about documents, in which order; what re-applies after settings change) and runs the window's lifecycle. It
  decides nothing: `ArchitectureTest` keeps it under 200 lines and no source file over 900.

Logic that does not need a window lives in a JavaFX-free class and the controller calls it: `io.RecentProjects` (recent
list), `io.ProjectFinder` (Go to File), `io.ProjectPaths` (display paths, URI to file), `editor.Symbols` (symbol under
the caret), `settings.NameList` (comma-separated settings), `editor.Brackets` (bracket matching),
`editor.LineNavigation` (Go to Line parsing and line/column clamping), `language.Completions` (where a completion goes,
and where it lands after more typing), `language.DiagnosticStore` (the diagnostics per file, moved along with edits)
and `launch.ProjectInstances` (which folders other windows have open: one OS-locked file per project under
`<config>/instances/`, so a crashed window never leaves a project marked open). New behaviour follows the same rule, and
`FEATURES.md` lists the test that verifies it; `FeaturesDocTest` checks that every one of those tests exists.

### Adding something

1. Behaviour a test can decide goes in a core package, with its unit test.
2. The window part goes in the feature package that owns it (or a new `ui.<feature>` package that depends only on `shell`
   and `editor`). Take what it needs through the constructor; react to documents by implementing `EditorListener`.
3. Register it in `App` and add its menu items in `MainMenu`.
4. Add a row to `FEATURES.md` naming the test. `mvn test` runs `ArchitectureTest` and `FeaturesDocTest`.

## Window layout and the resizable sidebar (v1.0)

The window's centre is two nested `SplitPane`s, so there is one kind of divider with one look and one behaviour:

```text
WorkArea (horizontal)  =  sidebar (project tree) | BottomPanel (vertical)  =  editors / terminal and other panels
```

`ui.shell.WorkArea` holds the sidebar's width in pixels and applies it as the divider position, so a window that grows
gives the extra space to the editors (the sidebar is `resizableWithParent = false`) and a hidden sidebar comes back
with the width it had. The sidebar's minimum is its own `minWidth`; the editors' minimum is the `minWidth` of the
content, which `SplitPane` honours while dragging. Focus Mode needs no code here: `ProjectTree.toggleVisible` changes
the sidebar's `visible` property, and `WorkArea` takes the sidebar out of (or puts it back into) the split when that
property changes. The terminal's divider lives in the inner `SplitPane` and is therefore independent of the outer one,
and the terminal is always exactly as wide as the editors above it. The width arithmetic (`clampWidth`,
`dividerPosition`) is static and tested without a toolkit; `WorkAreaTest` drives the real controls with mouse events.

## Syntax scanners (v1.0)

`editor.SyntaxHighlighter` picks the language from the file's extension and calls one scanner per lexical family. The
C-family, Python, markup and Markdown scanners are still inside it; YAML (`YamlSyntax`) and shell (`ShellSyntax`) are
classes of their own, so the file stays well under the 900-line limit and a new language is a new class plus one line in
the extension table and one in `spansIn`. Every scanner is a single pass without recursion and bounds every
unterminated construct by its line, which `SyntaxHighlighterTest` and `ShellSyntaxTest` check on pathological input.
