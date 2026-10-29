# Features

What Javelin IDE does today, how to use it, and which automated test proves it. When a feature changes, change its row
and its test together. "Workflow" tests are in `e2e/ProjectWorkflowE2ETest` and drive the same JavaFX-free services the
window uses; features that need a display (`App`, `ui/*`) are listed under *Checked by hand* below.

## Files and projects

| Feature | How | Verified by |
| --- | --- | --- |
| Open, edit, save (atomic write), Save As, new document | File menu | `FileIOTest`, `ProjectWorkflowE2ETest.createEditSaveAndReopenAFile` |
| Large files (warn at 1 MB, refuse above 32 MB) | automatic | `FileIOTest` |
| Open a project folder, recent projects (last 8, missing folders hidden) | File > Open Project / Recent Projects, `javelin [dir]` | `RecentProjectsTest`, `LaunchArgumentsTest`, `ProjectWorkflowE2ETest.recentProjectsRememberWhatWasOpened` |
| Switching to a different project first closes the previous project's files: each unsaved file asks Save / Discard / Cancel, and Cancel keeps the old project (and its remaining files) open. Reopening the same project leaves files alone | File > Open Project / Recent Projects | `ProjectChangeTest`, `ProjectSwitchE2ETest` |
| One window per project, several at once | run `javelin` in each folder | `MultiInstanceTest`, `OpenRequestsTest` |
| Opening a folder that another window already has asks first (Open Anyway / Cancel); a `javelin` window started only for such a folder closes on Cancel. A crashed window never leaves a project stuck | automatic | `ProjectInstancesTest`, `SameProjectTwiceE2ETest` (a real second process), `MultiInstanceTest` (wiring) |
| Project tree: create, rename, move, delete (confined to the project) | right-click, drag | `ProjectFilesTest`, `TreeGesturesTest`, `ProjectWorkflowE2ETest` (rename/move/delete) |
| Tree follows outside changes (terminal, git, file manager) | automatic, ~2 s | `ProjectWatcherTest`, `ProjectWorkflowE2ETest.changesMadeOutsideTheEditorRefreshTheTree` |
| Hide folders from tree and Go to File (`tree.exclude`) | settings | `NameListTest`, `ProjectWorkflowE2ETest.aProjectCanChangeWhatTheTreeHides` |
| Go to File (exact name, skips excluded folders) | Go to > File | `ProjectFinderTest`, `ProjectWorkflowE2ETest.goToFileFindsFilesButSkipsExcludedFolders` |
| Image viewer: PNG, JPEG, GIF, BMP, fit / 100% / zoom | open an image | `ImageFilesTest` |
| Crash recovery of unsaved tabs | offered at next start | `RecoveryStoreTest`, `RecoverySessionTest`, `ProjectWorkflowE2ETest.unsavedWorkSurvivesACrashAndIsGoneOnceSaved` |

## Editing

| Feature | How | Verified by |
| --- | --- | --- |
| Syntax highlighting (Java, Kotlin, JS/TS, C/C++, Go, Rust, PHP, JSON, Python, HTML, XML) | by extension | `SyntaxHighlighterTest` |
| Search, match case, replace | Edit menu | `TextSearchTest`, `ProjectWorkflowE2ETest.findAndReplaceAcrossASavedFile` |
| Auto indentation, newline handling | typing | `IndentationTest`, `NewlineTest` |
| Symbol under the caret (hover, definition, references, rename) | Language menu | `SymbolsTest`, `LanguageServiceTest` |
| Go to Line (invalid numbers are refused); opening a Problem or Reference lands on the nearest valid line and column | Go to > Line | `LineNavigationTest`, `EditingWorkflowE2ETest` |
| Matching bracket for `()`, `[]`, `{}` (selects both brackets; textual, see BUGS.md) | Go to > Matching Bracket | `BracketsTest`, `EditingWorkflowE2ETest.matchingABracketSelectsTheWholeBlock` |
| Completion: the popup starts at the word before the caret; choosing replaces it plus anything typed since, and never uses a range outside the document | Language > Complete | `CompletionsTest`, `EditingWorkflowE2ETest` |

## Build, run, terminal

| Feature | How | Verified by |
| --- | --- | --- |
| Detect Maven, Gradle, Make, Cargo, npm; build and run | Run menu | `BuildSystemTest`, `BuildPipelineE2ETest` |
| Problems from javac, Maven, gcc/clang, tsc, Kotlin, Go, rustc, Python | Problems panel | `ProblemParserTest`, `BuildPipelineE2ETest` |
| Process management, Stop All | Run menu | `ProcessManagerTest` |
| Terminal tabs, history, completion, venv display | Terminal panel | `TerminalSessionTest`, `TerminalCompletionTest`, `ShellCommandTest`, `AnsiTest` |
| Your dev tools work however the app was started: terminals, builds and language servers get the environment of your login shell (PATH, `JAVA_HOME`, SDKMAN, nvm ...), so `mvn`, `java`, `git` and friends are found when the app is opened from Applications, the Dock or Spotlight, not only from `javelin` in a terminal. If the login shell cannot be read, the inherited PATH plus Homebrew's directories is used | automatic | `ShellEnvironmentTest`, `FinderLaunchEnvironmentE2ETest` (a fake login shell and a fake `mvn`, through a real process and a real terminal session), `ProcessManagerTest` |

## Language intelligence (LSP, started by you)

| Feature | Verified by |
| --- | --- |
| Diagnostics, completion, hover, definition, references, rename, signature help, symbols, formatting | `LspTest`, `LanguageServiceTest`, `JsonRpcTest`, `DiagnosticShifterTest`, `TextDocumentTest` (a real child process, `FakeLanguageServer`) |
| Server diagnostics become Problems entries (errors stay errors, positions 1-based, source shown) | `LanguageProblemsTest`, `LanguageWorkflowE2ETest.diagnosticsReachTheProblemsPanelWithProjectRelativePaths` |
| Rename: the proposed edits are grouped by file and summarized before anything changes, then applied as small replacements to editor tabs (unsaved). Several inserts at one position keep the server's order | `RenamePlanTest`, `TextEditsTest`, `LanguageWorkflowE2ETest.aRenameIsPlannedShownAndAppliedToEveryAffectedFile` |

## Customization

| Feature | Verified by |
| --- | --- |
| User and project settings, themes, rebindable shortcuts, editor preferences | `SettingsTest`, `ThemesTest`, `KeybindingsTest`, `DefaultShortcutsTest`, `EditorPreferencesTest` |

## Privacy and offline guarantees

| Guarantee | Verified by |
| --- | --- |
| No network use, no unexpected dependencies | `OfflineGuardTest`, `DependencyAuditTest` |

## Checked by hand (needs a display)

Window layout, dialogs, tab context menu, drag and drop in the tree, image zoom, theme switching, Dock behaviour on
macOS. The checklist is in `HARDENING.md`.
