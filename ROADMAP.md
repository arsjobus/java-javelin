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
- [ ] No mandatory account
- [ ] No mandatory cloud service
- [ ] No telemetry by default
