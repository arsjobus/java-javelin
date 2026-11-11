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

## AI (v0.7)

`com.javelin.ai` is JavaFX-free and has no vendor in its middle. `AiService` is the whole workflow without a window: `prepare` turns an action and the editor's text into an `AiPlan` (what exactly will be sent, to which provider and model, with warnings) and refuses what must not be sent (no model, a credentials file, nothing selected, a selection too long to replace safely); `run` sends the plan and turns the reply into text or an `AiProposal`. It never applies anything: a proposal records the text it was made for and `isStillValid` says whether it may still be applied. Smaller pieces each do one thing: `PromptBuilder` (the wording, with the code marked as data), `AiContext` (selection or file, cut to the budget), `ResponseParser` (finds the one complete fenced code block, otherwise no proposal), `ChangeReply` (reads a reply as edit blocks, a diff or a whole block and returns the new text; `EditBlocks`, `UnifiedPatch` and `LineMatch` do the by-content matching), `ChangeChecker` (the checks whose `Finding`s are shown with the diff; it uses `editor.SyntaxHighlighter` to tell code from strings and comments and the JDK's compiler to parse Java), `LineDiff` (the unified diff the user reviews), `TestFileNames`, `SensitiveFiles`, `AiConfig` (the user-only `ai.*` settings), `LoopbackEndpoint` (what an address may be).

`AiProvider` is the seam. `OllamaProvider` implements it with the JDK's HTTP client against Ollama's streaming chat API; it is created in one place, `AiProviders`, from the user's settings, and it is the only class that may touch the network (`OfflineGuardTest` checks this, and that the client has no proxy and no redirects). Tests use a scripted provider for the logic and a fake Ollama on a loopback port for the real client. `ui.ai.AiController` runs a request on its own worker thread, so the window never waits, and applies a proposal as an ordinary editor edit.

### Project context (v0.9)

`com.javelin.ai.project` is part of the `ai` slice and equally JavaFX-free. It is a pipeline of small, pure steps: `ProjectScanner` (with `GitIgnore` and `FileKinds`) makes a `ProjectIndex`; a selector (`FullProjectSelector` or `SmartSelector`, the latter using `Vocabulary`, `CodeFacts` and `CodeIndex`) returns ordered `Pick`s with reasons; `ContextBuilder` fits them to a `ContextBudget` and writes the text and a `ProjectSnapshot`; `ProjectPromptBuilder` wraps it as an `AiRequest` that carries its own window; `ProjectAiService` returns a `ProjectPlan` (what would be sent, for the preview) and runs it. Replies become a `ProjectProposal` through `MultiFileReply`, which reuses the single-file `ChangeReply` per file; `ProposalApplier` checks and writes it and `AppliedChanges` reverses it. Every file access goes through `ProjectReader.resolve`, the one place that decides a path is inside the project. The model's own maximum context comes from `AiProvider.contextLimit`, so no provider detail leaks into the pipeline. The window side is `ui.ai.ProjectAiController` and `ProjectAiDialogs`.

### Verification loop (Phase 2)

`com.javelin.ai.verify` is part of the `ai` slice and JavaFX-free. `Verifier` takes the text of the changed files (project-relative path to text) and returns a `Verification`: `PASSED`, `COMPILE_FAILED`, `TESTS_FAILED` or `NOT_CHECKED` (the machine could not look: no compiler, a class path that is not known, no Java file changed; this is never counted against the model). `JavacVerifier` compiles the changed files as in-memory sources with the JDK's compiler, the project's source folders on the source path, the files that mention a changed class added as units (a broken caller is invisible to javac otherwise) and the class output kept in memory; it never writes to the project, runs with `-proc:none`, and subtracts the errors the project already had by compiling the original text once. `ProjectClasspath` finds build output folders, `lib/*.jar` and the direct Maven dependencies in the local repository, and says when it cannot find them all. Tests, when `ai.verify.tests` is on, are the test classes that are changed or mention a changed class; their classes go to a temporary folder outside the project and run in a child JVM with a time limit and a small bundled runner (JUnit 5 and 4 `@Test`), and the folder is deleted afterwards.

`VerificationLoop` is generic: a `Step` asks the model and checks the answer; the loop makes at most `retries + 1` requests, and `RepairPrompt` builds the repeat as the original request unchanged plus the first five problems with file and line and a numbered window of the failing code, never the whole previous answer. A request that fails on a repeat keeps the earlier attempts. `AiService.runVerified` and `ProjectAiService.runVerified` wrap their `runStreaming` with it and return a `Verified` (every attempt, and the one shown: the passing attempt, else the last one that held a change); a failed check becomes an error finding on the shown change, so the existing review and "Apply anyway" apply unchanged. The windows only call these and show `Verified.report()`. Not built: asking Maven to compile when the class path is unknown (the check says "not checked" instead), and a retry budget per model profile (Phase 5 brings profiles; `ai.verify.retries` is the one setting until then).

### Planner and step executor (Phase 3)

`com.javelin.ai.plan` is part of the `ai` slice and JavaFX-free. `Planner` sends one request holding the project's file names and the signatures (from `MethodFinder`, which is `CodeSpans` made public) of the files the selector picks, and nothing else; its reply is read by `PlanParser` (lines, or JSON) into a `TaskPlan` of `PlanStep`s, each one file and one method or `new`. `PlanValidator` decides whether a plan may run, using the shared `FailureReason` names, and `StepExecutor` runs it: per step a `StepContext` (the method, the file's package and imports, the signatures of the file and of the project types the method mentions, built from the file as the earlier steps left it) becomes a `StepPrompt` that uses the project request's own system message and answer format, the reply goes through `MultiFileReply` with an overlay of the earlier steps' text, only a change to the step's own file is taken, and `VerificationLoop` checks it with the changes so far before the next step begins. Nothing is written to the project: the steps are merged into one `ProjectProposal` for the existing review. `PlanReview` is the plan window's logic without the window. Project text is put between marker lines carrying a number that no sent text contains (`DataFence`), so a method cannot close its own block. The step limit is a hard check in the validator and again in the executor.

Anti-pattern, not built: asking a small model for a whole multi-file refactor in one reply. The plan exists so that no request asks for more than one method.

### Symbol index and symbol-level context (Phase 4)

`SourceOutline` (in `com.javelin.ai`, next to `CodeSpans` whose scanning it builds on) lists the members of a Java file with their line ranges and says when a line at member level is not one it can place. `com.javelin.ai.symbols` is part of the `ai` slice and JavaFX-free: `SymbolIndex` answers which symbols a file declares, which project types a method uses and who calls it directly (by name), from text it is given and an `OutlineSource` (the scanner today; a language server fits the same seam); `SymbolBudget` holds the limits per profile and `SymbolConfig` the `ai.symbols*` settings. In `com.javelin.ai.project`, `SymbolPlanner` decides which methods go in full, which callers and types are added and what a limit leaves out; `SymbolSkeleton` writes a file as a view (every non-body line kept exactly, so a view cannot hide a field or a declaration); `ContextBuilder` replaces the text of a file with its view, keeps the hash of the whole file in the snapshot and records the file as partial, so `MultiFileReply` refuses to take a whole-file rewrite of it. `TokenCalibration` turns the provider's prompt token counts into the characters per token that `ContextBudget` estimates with. Nothing here calls a model, a clock or the disk beyond reading the files it is handed.

### Edit reliability eval (test scope)

`com.javelin.ai.eval` (under `src/test/java`) measures the pipeline above; nothing in `src/main` depends on it. `EvalCorpus` and `EvalTask` read the fixture projects in `src/test/resources/eval`; `EvalRunner` asks `AiService` (a selection, or generated tests) or `ProjectAiService` (a project request) through a `MeteredProvider`, which asks for the run's sampling and counts calls and tokens; `FixtureBuild` compiles with the JDK's compiler and runs the tests in a child JVM with a time limit; `EvalReport` turns the results into JSON and the baseline Markdown. `EvalRunTest` is the one test that calls a model; it is tagged `eval` and excluded from `mvn test`. The main code gained only what the eval needs and the later phases reuse: `FailureReason` (also on `AiException`, `AiResult`, `ProjectAiResult` and `ProjectProposal`), `Sampling` on `AiRequest`, and `AiUsage` on `AiReply`.

## Customization (v0.8)

`com.javelin.settings` is JavaFX-free, like `language`. `Settings` layers a user file and a project file over defaults held by the caller; the file format is deliberately trivial (`key=value`, `#` comments, values verbatim) and writes preserve the user's comments and ordering. `Keybindings` is a registry of action ids and shortcut strings, `EditorPreferences` is the validated `editor.*` view of the settings, and `Themes` discovers built-in and custom themes. `ui.shell.KeyBinder` registers each menu item with `Keybindings`, and `ui.Shortcuts` is the only place shortcut strings become JavaFX key combinations. `ui.SettingsDialog` is a thin editor over the same files.

Two rules keep customization from becoming ambient behavior: `theme` and `key.*` are read from the user file only (a project cannot restyle or rebind the editor by being opened), and project commands (`run.command`, `build.command`, `language.server.*`) are either pre-filled into a confirmation dialog or echoed in the Output panel when used. The `java.util.prefs` store still holds recent projects and last-used commands. See `CUSTOMIZATION.md`.

## Layers and the window (v1.0)

Three layers; dependencies only point inward, and `ArchitectureTest` fails the build when one points outward.

```text
Bootstrap (App, Launcher)  ->  ui (JavaFX, one package per feature)  ->  core (JavaFX-free services)
```

- **Core** is `ai`, `build`, `editor`, `io`, `language`, `launch`, `settings`, `terminal` and `icon`. It never imports JavaFX,
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
  | `ui.ai` | `AiController` (every AI menu command, the review and the apply), `AiDialogs` (preview, review, model choice), `ProjectAiController` and `ProjectAiDialogs` (project requests: the file preview, the multi-file review, undo); the AI tab is `ui.AiPanel` |
  | `ui.menu` | `MainMenu`: labels, ids, default shortcuts, and which controller method each item calls |

  Inside `ui`, `shell` knows no feature; `editor` knows only `shell`; `language`, `run`, `recovery`, `ai` and `customize` know
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

`editor.LanguageDetector` picks the language from the file's extension, start-up file name or `#!` line, and `editor.SyntaxHighlighter` calls one scanner per lexical family. `editor.Brackets` uses the same spans to skip brackets in strings and comments. The
C-family, Python, markup and Markdown scanners are still inside it; YAML (`YamlSyntax`) and shell (`ShellSyntax`) are
classes of their own, so the file stays well under the 900-line limit and a new language is a new class plus one line in
the extension table and one in `spansIn`. Every scanner is a single pass without recursion and bounds every
unterminated construct by its line, which `SyntaxHighlighterTest` and `ShellSyntaxTest` check on pathological input.

## Dialogs and the main window (v1.0)

All dialogs and native choosers are shown through `ui/Dialogs` (`DialogOwnershipTest` fails the build otherwise). `Dialogs` keeps the stack of open dialog windows and gives each new dialog the innermost one as its owner (the main window when none is open). `DialogNesting` counts open dialogs so the main window is restored once, after the last has closed, and `WindowRestore` touches the window only if it is minimized or unfocused. The decision logic (`DialogNesting`, `WindowRestore`) has no JavaFX dependency and is unit tested.
