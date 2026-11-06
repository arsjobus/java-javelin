# AI Next Phase — Repository-Wide Context

## Goal

Extend Javelin's existing local AI integration so Qwen 3.5 9B (through Ollama) can understand an entire project repository.

The user should be able to describe a new feature in natural language without manually selecting and supplying all the relevant source files.

The AI should understand the project's structure, architecture, existing implementation, conventions, and tests.

## Guiding Principles

- Local-first: use the existing Ollama integration.
- User-controlled: AI requests are always explicitly initiated.
- Transparent: show exactly which files are included in the context.
- Safe: never expose sensitive files by default.
- Human-directed: AI-generated changes must be reviewed before application.
- Simple: avoid unnecessary dependencies, vector databases, and cloud services.
- Testable: every feature includes unit tests, acceptance criteria, and regression coverage.

## Phase 1 — Repository Scanner

Build a service that scans the currently opened project.

Features:
- Recursively discover project files.
- Build a directory and file inventory.
- Identify programming languages and file types.
- Respect `.gitignore`.
- Exclude generated files, binaries, build directories, and caches.
- Exclude sensitive files such as `.env` files and private keys.
- Allow users to configure exclusions.
- Prevent scanning outside the selected project directory.

**Tests:**
- Directory traversal and file discovery.
- Exclusion rules.
- Binary and sensitive file detection.
- Symlink and path traversal protection.
- Large repository handling.

## Phase 2 — Project Context Builder

Convert the repository inventory into structured AI context.

Include:
- Project directory structure.
- Source files.
- Documentation.
- Build configuration.
- Dependency information.
- Existing tests.
- Architecture documentation.

Features:
- Preserve file paths in the generated context.
- Clearly separate individual file contents.
- Produce a consistent project overview.
- Keep context generation independent of JavaFX and Ollama.

**Tests:**
- Context construction.
- File boundary preservation.
- Correct project metadata.
- Exclusion handling.
- Deterministic output.

## Phase 3 — Context Budget Management

Ensure the repository context fits within the model's actual available context window.

Features:
- Support configurable context sizes.
- Account for Ollama's runtime `num_ctx`.
- Distinguish model maximum context from runtime context.
- Reserve tokens for AI responses.
- Estimate token usage.
- Show context consumption to the user.
- Report files that cannot fit.
- Never silently truncate repository contents.

**Tests:**
- Token budget calculations.
- Output token reservation.
- Oversized repositories.
- Runtime context configuration.
- File omission reporting.

## Phase 4 — Full Project AI Context

Introduce a new AI context mode: **Full Project**.

Features:
- Send all eligible repository files that fit within the context budget.
- Include the project structure and documentation.
- Display a complete context preview before sending.
- Allow users to inspect included and excluded files.
- Allow users to adjust file inclusion.
- Clearly identify partial contexts when the entire repository does not fit.

The existing selection-based and file-based AI workflows must remain available.

**Tests:**
- Full repository inclusion when it fits.
- Correct handling of oversized repositories.
- Context preview accuracy.
- User cancellation.
- Regression tests for existing AI workflows.

## Phase 5 — Smart Project Context

Introduce a second mode: **Smart Context**.

Instead of including every file, intelligently select files relevant to the user's request.

For example, asking:

"Add a UML generator to the editor."

Should prioritize:
- Editor architecture.
- Java source parsing.
- Existing diagram or visualization functionality.
- AI integration.
- Relevant UI components.
- Related tests.

Selection should initially use deterministic techniques:

- File paths and names.
- Imports.
- Package relationships.
- Class and method references.
- Documentation.
- Existing tests.
- Build configuration.

Every selected file should have an understandable reason for inclusion.

Users must be able to review and adjust the selection.

**Tests:**
- File relevance ranking.
- Dependency and import relationships.
- Test discovery.
- Deterministic selection.
- Manual inclusion and exclusion.
- Context budget handling.

## Phase 6 — Context Refresh and Project Awareness

Keep the project context accurate as files change.

Features:
- Detect modified, added, and deleted files.
- Refresh the project index.
- Identify outdated context snapshots.
- Associate AI responses with the context snapshot used.
- Avoid unnecessary rescanning when possible.

**Tests:**
- File change detection.
- Snapshot invalidation.
- Context refresh.
- Incremental versus full scanning consistency.

## Phase 7 — Multi-File AI Proposals

Allow AI to propose coordinated changes across multiple files.

Example:

"Add an ad-hoc UML generation feature, including the UI, parsing logic, and tests."

The AI should be able to propose changes to all relevant files.

Features:
- Structured multi-file proposals.
- Individual file diffs.
- Explicit approval before applying changes.
- Path and project-root validation.
- Detection of files changed since context generation.
- Conflict handling.
- Safe application and rollback.

No automatic command execution.

**Tests:**
- Proposal validation.
- Multi-file diff generation.
- Path traversal protection.
- Stale file detection.
- Conflict handling.
- Apply/reject behavior.
- Regression coverage for existing single-file proposals.

## Phase 8 — Evaluation and Improvements

Evaluate the quality of repository-wide context.

Features:
- Test representative feature-development requests.
- Compare Full Project and Smart Context results.
- Measure context construction time.
- Evaluate relevance and completeness.
- Improve file selection based on observed failures.

Only consider embeddings, vector databases, or more complex retrieval systems if simpler deterministic methods prove insufficient.

**Tests:**
- Repeatable context selection fixtures.
- Regression tests for previously missed files.
- Large repository performance tests.
- Offline indexing tests.

## Architecture Guidelines

Preserve the existing AI architecture:

- `AiService`
- `AiProvider`
- `OllamaProvider`
- `AiProviders`
- `AiController`

Introduce repository-context functionality in the JavaFX-free AI core.

Potential components:

- `ProjectScanner`
- `ProjectFile`
- `ProjectIndex`
- `ProjectContext`
- `ContextBuilder`
- `ContextSelector`
- `ContextBudget`
- `ProjectSnapshot`
- `ProjectPromptBuilder`

These are proposed names, not mandatory abstractions. Keep implementation consistent with the existing codebase.

Do not couple repository context functionality directly to Qwen or Ollama.

## Security and Privacy

- Local Ollama remains the default.
- No background code transmission.
- No telemetry.
- No cloud indexing.
- No automatic source modifications.
- Sensitive files excluded by default.
- Repository content treated as untrusted data, not instructions.
- Context always visible before transmission.

## Overall Acceptance Criteria

The completed feature should allow a user to:

1. Open a project in Javelin.
2. Select Full Project or Smart Context.
3. Inspect exactly what will be supplied to the model.
4. See the context budget and any omitted files.
5. Submit a natural-language feature request.
6. Receive a response grounded in the repository.
7. Review proposed changes before applying them.

Every implementation phase must include unit tests, acceptance criteria, and regression coverage.

Run `mvn test` throughout development and `mvn verify` before completing the overall feature.

## Recommended Build Order

1. Repository Scanner
2. Project Context Builder
3. Context Budget Management
4. Full Project AI Context
5. Smart Project Context
6. Context Refresh
7. Multi-File AI Proposals
8. Evaluation and Improvements

**First milestone:** Get Full Project Context working reliably with Ollama. Once Javelin can safely and transparently provide the entire repository when it fits, build smarter context selection on top of it.
## Status

All eight phases are built. `FEATURES.md` (AI section) lists each behavior with the test that proves it; the design is in `AI.md` (project requests) and `ARCHITECTURE.md` (project context). The code is `com.javelin.ai.project` (JavaFX-free) plus `ui.ai.ProjectAiController` and `ProjectAiDialogs`; the AI menu has *Project Request...* and *Undo Last Project Change*.

| Phase | Where it lives | Acceptance, shown by |
| --- | --- | --- |
| 1 Repository Scanner | `ProjectScanner`, `GitIgnore`, `FileKinds`, `ProjectReader` | `ProjectScannerTest`, `GitIgnoreTest`, `ProjectReaderTest` |
| 2 Project Context Builder | `ContextBuilder`, `ProjectDependencies`, `ProjectContext` | `ContextBuilderTest`, `ProjectDependenciesTest` |
| 3 Context Budget | `ContextBudget`, `AiProvider.contextLimit`, `AiRequest.contextTokens` | `ContextBudgetTest`, `OllamaContextLimitTest` |
| 4 Full Project context | `FullProjectSelector`, `ProjectAiService`, `ProjectPlan` | `FullProjectSelectorTest`, `ProjectAiServiceTest` |
| 5 Smart Project Context | `SmartSelector`, `Vocabulary`, `CodeFacts`, `CodeIndex` | `SmartSelectorTest`, `VocabularyTest`, `CodeIndexTest` |
| 6 Context Refresh | `ProjectSnapshot`, `ProjectRefresh`, `ProjectScanner.refresh` | `ProjectSnapshotTest` |
| 7 Multi-File Proposals | `MultiFileReply`, `ProposalApplier`, `AppliedChanges` | `MultiFileReplyTest`, `ProposalApplierTest`, `ProjectAiWorkflowTest` |
| 8 Evaluation | `SelectionEvaluation` | `SelectionEvaluationTest` |

Decisions worth knowing:

- **Token counts are estimates** (3 characters per token, on the high side). No tokenizer is bundled, in keeping with "avoid unnecessary dependencies"; the preview says "estimates".
- **Runtime window versus model maximum.** `ai.contextTokens` is what a request asks Ollama for (`num_ctx`); the model's own maximum is read once per model from `/api/show` and the smaller number counts. Defaults: 32768 window, 4096 kept for the answer.
- **Smart Context is deterministic and has no index.** It scores names, folders, declared types, mentioned words, imports (Java, Kotlin, Scala, JavaScript, TypeScript, Python), tests and documents, and softens the score of large files so one big file that merely mentions the request's words does not push out several small files the answer depends on. That softening came from the first evaluation on this repository, where a needed dependency (`LineMatch`) was dropped for it; the case is kept in `SelectionEvaluationTest`.
- **Applying writes to disk** after per-file review, all files or none, so it can be reversed with *Undo Last Project Change* (which leaves a file edited since). A file with unsaved edits in a tab is never touched. Deleting or renaming files is not offered.
- **Not done:** a language-server-backed or embedding-based selection (the roadmap rules out vector databases), following imports in other languages, and running the project's build or tests on a proposal (no command is ever run by the AI).
