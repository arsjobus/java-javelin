# Roadmap

## v0.1 — Text editor

- [x] Maven project
- [x] JavaFX application
- [x] Basic editor window
- [x] Open file
- [x] Save file
- [x] Save As
- [x] New document
- [x] Undo/redo through JavaFX text control
- [x] Line-number gutter
- [x] Basic menu structure

## v0.2 — Project editor

- [x] Open directory
- [x] File tree
- [x] Multiple open files
- [x] Tabs
- [x] Dirty-state indicators
- [x] Project navigation
- [x] Recent projects

## v0.3 — Developer editor

- [x] Syntax highlighting
- [x] Search
- [x] Replace
- [x] Go to line
- [x] Go to file
- [x] Code folding
- [x] Bracket matching
- [x] Auto indentation
- [x] Multiple cursors

## v0.4 — Build and run

- [x] Integrated terminal
- [x] Run commands
- [x] Build commands
- [x] Process management
- [x] Output panel
- [x] Problems panel

## v0.5 — Language intelligence

- [x] LSP architecture
- [x] Diagnostics
- [x] Completion
- [x] Hover
- [x] Go to definition
- [x] Find references
- [x] Rename

## v0.6 — Git (Skipped)

- [ ] Status
- [ ] Diff
- [ ] Stage
- [ ] Commit
- [ ] Branches
- [ ] History

## v0.7 — AI

- [ ] Explicit AI invocation
- [ ] Local Ollama integration
- [ ] Explain selection
- [ ] Debug selection
- [ ] Generate tests
- [ ] Proposed changes
- [ ] Apply/reject workflow
- [ ] Provider-independent AI interface

## v0.8 — Customization

- [x] Themes
- [x] Keybindings
- [x] Settings
- [x] Editor preferences
- [x] Project settings

## v0.9 — Hardening

- [x] Crash recovery
- [x] Large-file testing
- [x] Performance profiling
- [x] Offline testing
- [x] Privacy audit
- [x] Dependency audit
- [x] End-to-end tests

See [HARDENING.md](HARDENING.md) for what each item covers, how it is verified, and what it found.

## v1.0 — Dependable tool

- [ ] Stable file/document model
- [ ] Stable project model
- [ ] Reliable language integration
- [ ] Reliable build/run workflow
- [ ] Git workflow
- [ ] Explicit local AI workflow
- [x] Documentation (see [FEATURES.md](FEATURES.md): every feature mapped to the test that verifies it)
- [ ] Release packaging
- [x] No mandatory account (nothing in the editor signs in; see [PRIVACY.md](PRIVACY.md))
- [x] No mandatory cloud service (`OfflineGuardTest` fails the build if main code touches the network)
- [x] No telemetry by default (`OfflineGuardTest`, `DependencyAuditTest`: no network code, exact pinned dependencies, no custom repositories)

### What is left for v1.0

Each open item above, with what would let it be ticked. Items marked *decision* need the maintainer to choose scope first.

| Item | Already verified by | Still needed (proposed) |
| --- | --- | --- |
| Stable file/document model | `FileIOTest`, `RecoveryStoreTest`, `RecoverySessionTest` | Policy for non-UTF-8 files (today they cannot be opened); undo history and selection are not recovered (documented in BUGS.md) |
| Stable project model | `ProjectFilesTest`, `ProjectWatcherTest`, `ProjectFinderTest`, `MultiInstanceTest`, `ProjectInstancesTest`, `SameProjectTwiceE2ETest` (the same folder opened twice is detected) | Instances share one settings file (last writer wins) |
| Reliable language integration | `LspTest`, `LanguageServiceTest`, `LanguageWorkflowE2ETest` (start, diagnostics, rename planned and applied to real files) | *Decision:* keep servers manual-start for 1.0 (documented) or add an explicit auto-start setting; `workspace/applyEdit` and code actions are listed as unsupported in BUGS.md |
| Reliable build/run workflow | `BuildSystemTest`, `ProcessManagerTest`, `ProblemParserTest`, `BuildPipelineE2ETest` | A pipeline test per supported build tool that is installed on the CI machine; no PTY (documented limit) |
| Git workflow | nothing (v0.6 was skipped) | *Decision:* build it, or move it out of 1.0 |
| Explicit local AI workflow | nothing (v0.7 is documentation only) | *Decision:* build it, or move it out of 1.0 |
| Release packaging | `packaging/macos/` recipe | Signed and notarized macOS build; a Windows and a Linux recipe, or state macOS as the only 1.0 platform |

### Maintainability: taking logic out of `App`

`App.java` is the one class the coverage gate cannot see (about 1,700 lines of window wiring, menus, themes, the project
tree, tabs, search, build/run, language and recovery). The rule in `AGENTS.md` is to keep UI wiring thin, so the order is
logic first, wiring second, one slice per change, each with its own tests:

1. Language: `TextEdits`, `RenamePlan`, `LanguageProblems` and `Completions` (the completion-candidate maths) (done).
2. Project tree: expanded-state and drop-target rules that do not need a `TreeItem`.
3. Go to line and bracket matching: `LineNavigation` and `Brackets` (done). Still in `App`: the search and replace dialogs.
4. Settings and themes application.
5. Tabs and crash recovery wiring; then split `App` into a window class plus one controller per area.
