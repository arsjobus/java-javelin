# AI

AI is an optional tool, not the center of the editor.

## Principles

- User invokes AI explicitly.
- Local models are preferred where practical.
- No ambient code surveillance.
- No automatic code changes.
- Proposed changes are reviewable.
- Provider choice remains with the user.
- AI failures must not prevent ordinary editing.

## Architecture

```text
AI Action
   |
   v
AI Service Interface
   |
   +-- Local Ollama
   +-- Other local provider
   +-- External provider (optional)
```

The editor should not couple its core document model to a specific AI vendor.

## Proposed change workflow

```text
Request
  -> Context selection
  -> AI response
  -> Diff/proposal
  -> Human review
  -> Apply or reject
```

The AI may explain, analyze, draft, refactor, or propose. Applying a change remains a user action.

## Dependable changes

A model's reply is not trusted to fit the file. For Propose Change: the selection is widened to whole lines before it is
sent; the reply is read as JSON edits, edit blocks, a unified diff or a whole code block, and applied by content, never by line number
(all or nothing; an edit that matches nowhere or in several places, or names a file that was not sent, is refused with a reason, and edits that were not exact copies of the code are flagged in the review); a reply that lost its indentation gets the selection's back; and before the review opens the result is
checked for placeholders, leaked markers, bracket balance, Java syntax, dropped methods and a large shrink. Errors do not block
anything: the review shows them and its button reads "Apply anyway". Nothing is retried or applied without a click. See
[AI-EDITING.md](AI-EDITING.md) for the reasoning and what is not done yet.

## Status (v0.7)

Implemented as described above, with Ollama on this computer as the one provider. See [FEATURES.md](FEATURES.md) (AI section) for each behavior and the test that proves it, and [PRIVACY.md](PRIVACY.md) for what is sent.

Use: install [Ollama](https://ollama.com) and a model (`ollama pull <model>`), then choose *AI > Choose Model...*. Select code and choose *Explain Selection*, *Debug Selection*, *Propose Change...*; *Generate Tests* works on the selection or the whole file. Every request first shows exactly what will be sent; the answer appears in the AI tab; a proposed change is a diff you apply or reject. Settings (`ai.*`) are in [CUSTOMIZATION.md](CUSTOMIZATION.md).

## Project requests (v0.9)

*AI > Project Request...* lets the model see the whole open project, or the part that matters to a request, so a feature can be described in words without selecting files. The same rules apply, and three more:

- **Everything sent is listed.** The Send dialog shows each file with its size in tokens and why it is there, each file that is *not* sent and why, what the scan left out (ignored, generated, binary, credentials, too large), the budget and the full message. You can tick files in or out and choose *Update Preview*.
- **Two modes.** *Smart Context* picks files by the request's words, names, declarations, imports, tests and the project's documents, and says why for each file. *Full Project* sends everything that fits, most important first; when it does not all fit the preview and the message both say it is partial.
- **Several files, reviewed one by one.** The answer can propose changes to several files. You see each file's diff, tick the ones to apply, and only those are written. A file with unsaved edits is never touched, a new file never replaces an existing one, a file that changed since the model saw it is re-checked by content, and *AI > Undo Last Project Change* takes the last apply back.

The logic is `com.javelin.ai.project` (JavaFX-free): `ProjectScanner` and `GitIgnore` find the files, `ContextBudget` and `ContextBuilder` fit them to the window, `FullProjectSelector` and `SmartSelector` choose, `ProjectSnapshot` and `ProjectRefresh` notice change, `MultiFileReply`, `ProposalApplier` and `AppliedChanges` turn an answer into reviewed, reversible changes, and `SelectionEvaluation` measures the selection. `ProjectAiService` ties them together next to `AiService`.

Limits to know: token counts are estimates (no tokenizer is bundled); Smart Context uses words, imports and naming conventions, not a language server or a compiler, so it can miss a file that shares none of them with the request (the preview is where you add it); import-following covers Java, Kotlin, Scala, JavaScript, TypeScript and Python; the model cannot delete or rename files through a proposal; nothing is run. Written to disk files appear in open tabs when those tabs have no unsaved edits.

## Measuring reliability (Phase 0 of the reliability roadmap)

How often a model's change compiles and passes its tests is measured, not guessed. `mvn -Peval test -Deval.model=<model>` runs 24 small fixture projects through this same pipeline, three times, with temperature 0 and a fixed seed, and writes the pass rate, the failure reasons and the per-task results. It lives in test scope, runs only when asked, and talks only to the loopback Ollama like everything else. See [docs/eval/README.md](docs/eval/README.md).

Every place that refuses a model's answer says why in one shared vocabulary, `FailureReason` (`NO_EDIT_BLOCK`, `UNPARSEABLE`, `ANCHOR_NOT_FOUND`, `WRONG_FILE`, `OVERSIZED`, `TRUNCATED`, `COMPILE_ERROR`, `TEST_FAILURE`, `REFUSED`, `TIMEOUT`); `AiResult.failure()` and `ProjectAiResult.failure()` carry it. Nothing the user sees changes: the editor's own requests still use temperature 0.2 with no seed, and the reason is not yet shown.

## Safeguards

| Safeguard | State |
| --- | --- |
| Context preview | Done: the Send dialog shows the full message |
| Token/context budget | Done: `ai.maxContextChars` (characters, not tokens), cut and marked; Ollama's window sized to fit. Project requests budget in estimated tokens against `ai.contextTokens`, the model's own maximum and `ai.reserveTokens`, and never cut a file in half |
| Provider indicator | Done: AI tab header and the preview |
| Network indicator | Done: the preview says where the request goes; the status bar shows it while a request runs |
| Per-action permission | Done: each request needs a click on Send; Enter means no |
| File-scope restrictions | Done: only the selection (or the file for Generate Tests) is sent; credentials files are refused, secret-looking text is flagged. A project request sends only the files its preview lists, never anything outside the project, ignored, generated, binary or credentials files |
| Undoable application | Done: applying is an ordinary edit, unsaved, undo restores it. A project change is written to disk after review, all files or none, and *Undo Last Project Change* restores them |
| Local only | Done: a non-loopback address is refused; no proxy, no redirects |
| External providers | Not built. `AiProvider` is where one would go, with its own indicator and a consent step before anything leaves the computer |

## Not built

Chat history, inline completion, agents that run commands or choose files on their own, deleting or renaming files through a proposal, a vector index or embeddings, and streaming a change into the editor. Each would break a principle above or needs its own design.
