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
com.javelin_ide
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

`com.javelin_ide.language` is JavaFX-free. `LanguageService` tracks open documents and routes them to user-started servers; `LanguageServerSession` owns one server process and the protocol handshake; `JsonRpc` and `Json` are a small stdio JSON-RPC client written against the JDK, because an LSP client needs very little JSON and the dependency rule asks for a reason to add one. `App` only translates between editor state and this package.

The text and planning logic that used to sit inside `App` now lives here as small static helpers: `TextEdits` (line/character to offset, ordering and applying server edits, the minimal change between two strings), `RenamePlan` (a rename proposal grouped by local file, with the text of the confirmation) and `LanguageProblems` (diagnostics to Problems entries). `App` still decides when to call them and shows the result.

Beyond the v0.5 roadmap items, `LanguageServerSession` also offers signature help, document symbols and whole-document formatting. Each is an explicit menu command under Language; formatting edits are applied to the editor buffer only (unsaved, undoable) and are discarded if the text changed while the server was working.

## Customization (v0.8)

`com.javelin_ide.settings` is JavaFX-free, like `language`. `Settings` layers a user file and a project file over defaults held by the caller; the file format is deliberately trivial (`key=value`, `#` comments, values verbatim) and writes preserve the user's comments and ordering. `Keybindings` is a registry of action ids and shortcut strings, `EditorPreferences` is the validated `editor.*` view of the settings, and `Themes` discovers built-in and custom themes. `App` registers each menu item with `Keybindings`, and `ui.Shortcuts` is the only place shortcut strings become JavaFX key combinations. `ui.SettingsDialog` is a thin editor over the same files.

Two rules keep customization from becoming ambient behavior: `theme` and `key.*` are read from the user file only (a project cannot restyle or rebind the editor by being opened), and project commands (`run.command`, `build.command`, `language.server.*`) are either pre-filled into a confirmation dialog or echoed in the Output panel when used. The `java.util.prefs` store still holds recent projects and last-used commands. See `CUSTOMIZATION.md`.

## Keeping `App` thin (v1.0)

`App` is the JavaFX wiring and nothing else may be decided there that a test could decide. Logic that does not need a
window lives in a JavaFX-free class and `App` calls it: `io.RecentProjects` (recent list), `io.ProjectFinder` (Go to
File), `io.ProjectPaths` (display paths, URI to file), `editor.Symbols` (symbol under the caret) and
`settings.NameList` (comma-separated settings), `editor.Brackets` (bracket matching), `editor.LineNavigation` (Go to Line
parsing and line/column clamping), `language.Completions` (where a completion goes, and where it lands after more typing)
and `launch.ProjectInstances` (which folders other windows have open: one OS-locked file per project under
`<config>/instances/`, so a crashed window never leaves a project marked open). New behaviour follows the same rule, and `FEATURES.md` lists the test
that verifies it.
