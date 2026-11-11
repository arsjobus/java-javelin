# Roadmap: Reliable AI Edits on Small Models

Status: proposed. Companion to [AI.md](AI.md) and the v0.7 / v0.9 AI items in [ROADMAP.md](ROADMAP.md). Works alongside any ROADMAP-AI-NEXT.md; where the two overlap, this document owns the reliability questions and that one owns repository-wide context.

## Why this roadmap exists

The AI workflow already has the plumbing: explicit invocation, context preview, edit blocks applied by content, pre-review checks, human apply/reject, and undo. What the repository does not yet show is whether the model's change is correct. I could not find a measured success rate for model edits in AI.md or ROADMAP.md, so "hit or miss" cannot currently be told apart from bad luck, and every prompt or model change is a guess.

The premise of this roadmap is that a weak model succeeds more often when the task is smaller, the output contract is tighter, and the checks are faster and automatic. The same pipeline should improve, not fork, when a frontier model is plugged in.

## Principles

1. **Measure before tuning.** No prompt, profile or format change merges without an eval result on a fixed corpus.
2. **Shrink the task, not the ambition.** The unit of model work is one hunk in one method. Multi-file work is a sequence of such units.
3. **Verify with the compiler and tests, not the model's word.** The model's reply is a proposal until javac and the tests have looked at it.
4. **One pipeline, different knobs.** Capability lives in a `ModelProfile` and provider capabilities. Code must not branch on the provider's name.
5. **Guardrails stay.** Nothing applies without a click; offline stays the default; an external provider needs a consent step; file-scope limits from AI.md still hold.

## What can be verified without frontier access

This roadmap is built around the models you can run yourself: Qwen 3.5 9B through Ollama. Frontier models are not available for measurement here, so nothing below claims a frontier result. Concretely:

- **Correctness is checked by tests, not by a model.** The eval asks whether the fixture's test passes after the change. That needs no frontier model to judge, and it works for every profile whoever runs it.
- **The small profile is fully measurable locally.** Phases 0 through 4 run entirely against Ollama on your machine.
- **The frontier path is verified for plumbing only.** Contract tests run against recorded or fake responses show the parser, the schema handling and the consent gate work. They do not show that a frontier model writes better edits. Any such claim waits for a run on real access.
- **Hybrid routing (Phase 6) and any frontier comparison are deferred** until someone can run them. The state machine can still be built and tested with fakes.

## Model profiles

A `ModelProfile` is resolved from settings (`ai.profile = small | frontier | custom`). It is suggested from the model name (a `qwen…9b` tag resolves to `small`) and can always be overridden.

| Field | small (9B local) | frontier |
|---|---|---|
| Context used per request | tight, symbol-level (Phase 4) | larger, file-level |
| Edits per proposal | 1 hunk | several hunks, several files |
| Primary edit format | search-replace with exact or fuzzy anchor | whole-method or search-replace; git apply optional |
| Structured output | JSON schema via Ollama `format` | provider-native structured output |
| Retry budget after a failed check | 2 | 1 |
| Planning | external plan or manual steps (Phase 3) | model plans itself |
| Step limit (Phase 3) | 3 steps, one file each | 8 steps |

Provider capabilities (`maxContextTokens`, `supportsSchemaOutput`, `reportsTokenCounts`) are declared by each `AiProvider` and read by the pipeline.

## Phase 0: Baseline and evaluation harness

**Goal:** know the success rate of each profile on the same work.

Deliverables:
- An eval corpus of 20 to 30 small fixture projects under `src/test/resources/eval/`. Each has a request, a JUnit test that fails before the change and passes after a correct one. Mix: add a method, fix a bug, extract a method, rename across two files, change a signature and its callers, add tests for existing code.
- A runner, behind an explicit Maven profile (`-Peval`), that runs the AI pipeline with a named profile and writes `target/eval/results-<date>.json`. Each task records: applied, compiled, tests passed, attempts, wall time, tokens if reported, and a failure reason.
- A failure taxonomy used everywhere: `NO_EDIT_BLOCK`, `UNPARSEABLE`, `ANCHOR_NOT_FOUND`, `WRONG_FILE`, `OVERSIZED`, `TRUNCATED`, `COMPILE_ERROR`, `TEST_FAILURE`, `REFUSED`.
- Baseline results for Qwen 3.5 9B on the corpus, committed as `docs/eval/baseline-<date>.md`. The run repeats three times to show variance. No frontier baseline is part of this phase (see "What can be verified without frontier access").

Constraints:
- Live model calls only happen when the eval profile is selected. Eval code lives in test scope, so `OfflineGuardTest` (which covers main code) keeps passing.
- Where the model supports it, use temperature 0 and a fixed seed.

Tests: `EvalCorpusTest` (every fixture fails before and passes after its reference fix), `EvalRunnerTest` with a scripted fake provider.

Exit: baseline published and reproducible within the stated variance.

**Status.** The corpus, the runner, the failure taxonomy and their tests are built (see [docs/eval/README.md](docs/eval/README.md)). The baseline is not: it needs a run against Qwen 3.5 9B (`mvn -Peval test -Deval.model=qwen3.5:9b`), and the Markdown it writes is committed as `docs/eval/baseline-<date>.md`. Two small departures from the text above: the taxonomy also has `TIMEOUT`, and add-tests tasks are judged by whether the generated tests pass on the real code and fail on a planted bug, since they have no test to fail before.

## Phase 1: Tighter edit contract

**Goal:** the model's reply is parsed with no ambiguity, or rejected with a reason.

Deliverables:
- For the small profile, search-replace blocks become the primary format. Unified diffs are not used for small models, because hunk headers and line numbers are the usual failure. Whole-method replacement is the fallback.
- Anchors match by content through a ladder: exact text, then whitespace-normalised text, then unique signature. The rung that matched is recorded in the proposal.
- Where the provider supports it, edits are requested as JSON with fields `file`, `anchor`, `old`, `new`. The text parser stays as the fallback.
- An edit that names a file outside the context, or an anchor that matches zero or several places, is refused with a reason and shown in the review. It is never guessed at.

Tests: parser tests over malformed, truncated and fenced replies; anchor-ladder tests showing which rung matched; a test that a refused edit writes nothing.

Exit: `UNPARSEABLE` and `ANCHOR_NOT_FOUND` together fall below a threshold set from the Phase 0 baseline.

Status (8 Nov 2026): built, defaults unchanged, exit not yet measured. The anchor ladder (exact, trailing spaces, indentation, runs of spaces, then a whole method by its signature) records its rung on the proposal and flags loose matches in the review. JSON edits (`ai.structuredEdits`, off) and the refusal of files outside the context are in. Search-replace blocks are not yet the default for small selections: that waits for an eval comparison (`docs/eval/README.md`, Phase 1). The baseline has 0 contract failures in 72 results, so the threshold is no worse than 0 of 72 with no drop in pass rate.

## Phase 2: Verification loop

**Goal:** most broken proposals are caught and repaired before a human sees them.

Deliverables:
- After parsing, the proposal is applied to an in-memory copy of the changed files, never to disk. Then the checks run: a compile of the changed files against the project classpath (incremental javac, or `mvn -q compile` when the classpath is unknown), then the tests the task names or the tests that reference the changed classes.
- On failure, a compact message goes back to the model: the first few diagnostics with file and line, plus only the failing hunk. Attempts are bounded by the profile's retry budget.
- The review dialog shows the verification result for each attempt. "Apply anyway" remains available, as AI.md already says.

Tests: `VerificationLoopTest` with a fake provider that returns a broken edit then a fixed one; a test that the retry count never exceeds the budget; a test that file contents and modification times are unchanged during verification.

Exit: on the corpus, the fraction of proposals that reach review with a compiling result is reported per profile.

Status (8 Nov 2026): built, exit not yet measured. `com.javelin.ai.verify` compiles a proposal in memory (the changed files plus the files that mention them, against the project's source folders and the class path it can find without a build tool or the network), never writes to the project, and runs no project code while compiling. A failed check repeats the request with the first five compiler messages and a window of the failing code, within `ai.verify.retries` (default 2, which stands in for the small profile's budget until Phase 5 brings profiles). Selection changes and project requests both use it; the review shows one line per attempt and a failed check is an error finding, so "Apply anyway" is unchanged. Running tests is built but off (`ai.verify.tests`): it runs code a model wrote. Departures: the `mvn -q compile` fallback is not built, so an unknown class path (a dependency not in the local Maven repository, a Gradle project) reports "not checked" and the proposal goes to review as before; and Maven is not run on a copy of the project. The eval can run the loop (`-Deval.retries=2`, see `docs/eval/README.md`) and reports the fraction that reach review compiling; that run has not been made.

## Phase 3: Task decomposition

**Goal:** a multi-file change becomes a short list of steps that a small model can finish one at a time.

Deliverables:
- A planner step. Given the request and the smallest useful context, it produces a plan in which each step names one file and one method (or declares a new method). A plan step that names a nonexistent file or symbol, unless marked "new", is rejected.
- A step executor that runs steps in order. Each step receives its own method and the signatures it depends on. Verification (Phase 2) runs after each step. A failure stops the run and shows what completed.
- The plan is shown in the Send dialog, following the "everything sent is listed" rule. The user can edit, reorder or remove steps.
- The step limits in the profile table are enforced, not advisory.

Anti-pattern, recorded in ARCHITECTURE.md: asking a small model for a whole multi-file refactor in one reply. It is not built.

Tests: `PlanParseTest`, `StepExecutorTest` with a scripted provider, step-limit enforcement tests, a test that a plan step with an unknown symbol is refused.

Exit: on the multi-file corpus tasks, the small profile's success rate is reported against the Phase 2 result.

Status (9 Nov 2026): built, off by default (`ai.plan`), exit not yet measured. `com.javelin.ai.plan` is JavaFX-free. The planner (`Planner`) sends the file names and the signatures of the files the selector picks (all source files when it picks none, up to 8 files), never a method body, and asks for one line per step, `file | method | task` (JSON when `ai.structuredEdits` is on and the provider supports it). `PlanValidator` refuses a step whose file is not in the project (`WRONG_FILE`) or whose method is not in its file (`ANCHOR_NOT_FOUND`) unless it says `new`, and what an earlier step declares new may be used by the steps after it; a plan with a problem is asked for once more with the problems and is otherwise shown for editing, never run. `StepExecutor` runs the steps in order. Each is its own request holding only its method, its file's package, imports and signatures and the signatures of the project types it mentions; the next step sees the file as the steps before it left it, held in memory (`MultiFileReply.parse` takes that as an overlay). The Phase 2 check and repair run after each step. A failed step stops the run and the review shows the steps before it. The step limit (`ai.plan.maxSteps`, default 3, at most 8) is checked when a plan is made, when it is edited and again when it runs. The Send flow is two dialogs, both listing what is sent: the planning request, then the plan as editable lines (reorder, edit or delete) beside what each step sends; Send is possible only for text that was just checked. The eval runs it with `-Deval.plan=3`.\n
Departures: the limit comes from a setting until Phase 5 brings profiles. A step that changes a signature would break its callers until a later step mends them, so a compile error in a file that a later step is planned to change does not fail the earlier step (in the step's own file, only errors outside the lines it changed are set aside); the last step is judged on everything. A file changed by several steps reaches the review as one whole-file change that applies only if the file on disk is still the one the plan started from. Not measured: the exit needs a run against the model. The figures to compare with are in the committed baselines, single attempt, no plan: on the 8 multi-file tasks (rename across files, change a signature) `baseline-2026-11-08-qwen3.5-9b-think-3.md` has 100% and 75%, `baseline-2026-11-08-qwen3.5-9b-nothink-2.md` 100% and 50%, and `baseline-2026-11-08-qwen2.5-coder-7b-nothink-2.md` 0% and 50%. No Phase 2 run is committed yet, so run once with `-Deval.retries=2` and once with `-Deval.retries=2 -Deval.plan=3` on the same model and compare.

## Phase 4: Retrieval built for small windows

**Goal:** the model sees the code that matters, in as few tokens as possible.

Deliverables:
- A symbol index for the project: classes, method signatures, fields, imports, and call sites. The index uses the language server when one is running (v0.5 LSP), and a lightweight parser otherwise. Decision for the maintainer: adding JavaParser versus relying on LSP plus a regex fallback (see open decisions).
- Context per request is built from the target method body, the signatures of the types it uses, and the signatures of its direct callers. Each is included only if the profile's budget allows.
- Token counts become real for the model in use. The current estimator (no tokenizer bundled) is calibrated against the `prompt_eval_count` Ollama reports after a call. Frontier providers report their own counts.
- Smart Context selects at symbol level, and the preview says which symbols were chosen and why.

Tests: `SymbolIndexTest` over a fixture project; `ContextBudgetTest` per profile; a test that the preview lists every symbol that was sent.

Exit: context size on the corpus falls by a measured amount with no drop in success rate.

**Status.** Built, off by default (`ai.symbols=true` turns it on for Smart Context; `ai.symbols.profile` is `small` or `frontier`): `SourceOutline` and `SymbolIndex` (types, method and constructor signatures, fields, initializers, enum constants, imports, call sites found by name), `SymbolPlanner` and `SymbolSkeleton` (the methods the request is about in full, their direct callers, the signatures of the types they use, every other method as its declaration with `{ /* body not shown */ }`), and `TokenCalibration` (the estimate is adjusted from the `prompt_eval_count` Ollama reports; the lowest of the last 8 answers wins, with a safety factor, and nothing is used before 2 answers). The preview lists every symbol sent of a file sent in part, with the reason. A reply that rewrites a file shown only in part as a whole is refused: it would drop code the model never saw. Tests: `SourceOutlineTest`, `SymbolIndexTest`, `SymbolSkeletonTest` (also over every Java file of this project), `SymbolContextTest` (limits per profile, the preview names every symbol, the refusal), `TokenCalibrationTest`, `SymbolConfigTest`, `SymbolCorpusSizeTest`.

Departures and limits:
- **The language server is not used.** The index reads its members from an `OutlineSource`; the only source built is the lightweight scanner. A source backed by LSP `documentSymbol` fits the seam but is not written, because the editor window that owns the server is not testable here. No JavaParser: the decision stays open, but the scanner reads 475 of this project's 479 Java files exactly (the rest are sent whole, and the preview says so).
- Calls are found by name and by whether the calling file could see the type, so overloads and unrelated methods with the same name can show up as callers (the preview says "found by name"). A call in a field initializer is not found. Only files Smart Context already picked, plus the callers and used types that the targets lead to, are considered.
- A method is a target when the request spells it (`Owner.method` outranks `method`, which outranks a word that is part of a name) or when a word of the request is a word of its name; constructors only when the request says "constructor" or "new". A request that names no method sends its files whole, with a note. This has not been tried on vague requests against a model.
- The limits per profile are guesses (`SymbolBudget`); `frontier` is the same code with larger limits and has not been measured against any frontier model. Profiles proper come in Phase 5.
- Calibration lasts as long as the editor runs and is used by project requests; selection requests and plan steps neither feed nor use it.

Measured (no model involved; see [docs/eval/README.md](docs/eval/README.md)): on the eval corpus the context does not change, because every fixture file is under the whole-file threshold (4,649 estimated tokens for the 8 project tasks with and without it). On this project's own code, with 8 requests that each name `Class.method`, the same selection costs 61% fewer estimated tokens with `small` and 39% fewer with `frontier` when the window is large enough for everything, and with a 32k window the same tokens carry 33 to 56 files instead of 18 to 26. **The exit is therefore not met:** the corpus cannot show a drop in size, and whether the pass rate holds needs a run against the model, once with `-Deval.retries=0` and once with `-Deval.symbols=small` on the same model and seed. Decision for the maintainer: add a corpus task with a file large enough to need a view (changing the corpus changes the baselines' task count).

## Phase 5: Model profiles and the frontier provider

**Goal:** a frontier model runs through the same pipeline, with the same checks, and is never used without consent.

Deliverables:
- Profile resolution as described above, with a settings override.
- An external `AiProvider` for one frontier API. Before its first use in a project, the Send dialog names the provider, the destination host, and exactly what will be sent. Nothing is enabled by default, and the key is stored as PRIVACY.md describes.
- `OfflineGuardTest` is updated so that the single approved external client is the only network-capable class allowed, and only while the external provider is selected. The test still fails if any other main-code class touches the network.

Tests: `ProviderContractTest` run against the Ollama provider and a frontier fake; `ConsentGateTest` (no request without consent, consent is per project); updated `OfflineGuardTest`.

Exit: the frontier provider passes its contract and consent tests with fakes and recorded responses, and the small profile's results are unchanged by the refactor (the Phase 0 corpus reruns with the same numbers). No side-by-side quality comparison is claimed until frontier access exists.

**Status.** Built. Profiles: `ModelProfile` (the table's rows, `small`, `frontier` and `custom`) and `ModelProfiles` (resolution and validation). `ai.profile` overrides; without it a provider that runs on this computer suggests `small` and any other `frontier`. The profile supplies the defaults of the retry budget, the plan step limit, the symbol budget and JSON edits; an explicit `ai.verify.retries`, `ai.plan.maxSteps`, `ai.symbols.profile` or `ai.structuredEdits` always wins. `small` equals what those settings defaulted to before, so choosing it changes nothing. Provider: `ExternalProvider` (one frontier API) on `JdkExternalTransport`, `ExternalConsent` (per project, provider and host), `ExternalKey`, `ExternalConfig`, `ExternalAccess`; `AiProvider` gained `reportsTokenCounts()`, `consentNotice()` and `recordConsent()` so the pipeline asks the provider what it is instead of knowing its name. `OfflineGuardTest` now allows exactly two network-capable classes, and the second may only be created when `ai.provider=external`. Tests: `ProviderContractTest` (Ollama against a fake server, the external provider against a fake transport), `ConsentGateTest`, `ExternalProviderTest`, `ExternalSettingsTest`, `ModelProfilesTest`, `OfflineGuardTest`, `ArchitectureTest`.

Departures and limits:
- **The provider is the Anthropic Messages API (`api.anthropic.com`), an assumption.** Open decision 3 was not answered, so one API was picked to make the work testable; the wire format is isolated in `ExternalProvider`, and another vendor is another class chosen in `AiProviders`. The host is a constant in the code, not a setting, so a settings line cannot point code elsewhere (no proxy, no redirect, HTTPS only).
- **Nothing has run against the live service.** Request and reply shapes (`x-api-key`, `anthropic-version`, `output_config.format`, `usage`, `stop_reason`) follow the vendor's documentation and are tested with fakes only. Replies are read whole, not streamed. JSON edits are off for this provider until `ai.external.structured=true`, because the edit schema has not been sent to the live service. No claim about edit quality is made; the frontier column of every table remains unmeasured.
- **`editsPerProposal`, `editFormat` and `planning` are recorded on the profile but nothing enforces them.** Enforcing one hunk for `small` would change what the Phase 0 corpus measures, so it needs an eval run first. The four fields that drive the pipeline are listed above.
- **The suggestion is by where the model runs, not by its name.** The roadmap's example (a `qwen...9b` tag resolves to `small`) holds because any local model does; the name only words the explanation (`ModelProfiles.describe`). A large local model is still `small` unless `ai.profile` says otherwise.
- **The key is never stored by the editor.** It is read from the environment variable `ai.external.keyEnv` names (default `ANTHROPIC_API_KEY`) or from a file `ai.external.keyFile` names, which must be outside the open project. An app started from the Dock or Finder does not see shell variables, so use the file there.
- **Consent is a second dialog**, shown after Send the first time in a project: it names the provider, the host and the project and shows the complete message again; "Allow for this project and Send" records it in `ai-consent.tsv` beside the user settings file. The provider itself refuses to send without it, whatever the window does. To take it back, delete the line. There is no menu item for it. Listing models sends only the key and no code, so it asks for no consent.
- **The small profile's numbers were not rerun.** The exit asks for the Phase 0 corpus to rerun with the same numbers. Ollama was not available where this was built; `ModelProfilesTest.theSmallProfileIsExactlyWhatTheSettingsDefaultedToBefore` and `aSmallProfileLeavesTheConfigurationExactlyAsItWas` pin the equivalence, and the rerun (`mvn -Peval test -Deval.model=...`) is still to be done.
- The window changes (`AiController`, `ProjectAiController`, `AiDialogs`) were compiled except for the parts that need RichTextFX, and were not launched: see the manual check in HARDENING.md.

## Phase 6: Hybrid routing

**Goal:** use frontier capability where it is cheap and useful, and keep as much work local as possible. Both modes are opt-in.

**Status: deferred** until frontier access exists to measure it. Only the routing state machine and its tests are built before then. Do not describe either mode as improving results.

- **Frontier plans, local executes.** The frontier model produces the Phase 3 plan from the request, the file tree and the symbol index. It does not see file bodies unless the user ticks them in the preview. The local model executes each step.
- **Local first, escalate on failure.** The local model tries first. When its retry budget is used up, the editor offers, and never does automatically, to send the same request to the frontier profile through the consent step.

Exit (when access exists): the eval shows hybrid success at least equal to frontier-only, with external input measured in tokens. Both numbers are recorded.

Tests: a routing state-machine test; a test that escalation requires a click.

## Phase 7: Keeping it honest

- Prompts live in `src/main/resources/prompts/<name>-<version>.txt`, so each eval result names the exact prompt it used.
- A change to a prompt, profile or format runs the eval. A regression beyond a set number of tasks blocks the change. The threshold is written into AGENTS.md.
- AI.md gains a short section, "What works on small models," with the measured numbers and the date they were taken. Claims in the docs point to the eval result they came from.

## Definition of done

- Baseline published for the small profile on the corpus, with variance.
- The small profile meets the target set after Phase 0. Proposed target, for the maintainer to confirm: at least 70% of corpus tasks pass their tests within two attempts.
- The frontier profile runs through the same pipeline with no code path that depends on the provider's name, verified by contract tests. Its quality is unmeasured and is stated that way in the docs.
- Every guardrail in AI.md still holds: no auto-apply, offline by default, consent before any external send, file-scope limits, undo for applied changes.

## Not in scope

- Agents that run commands or choose files on their own (AI.md: not built, and this roadmap does not change that).
- Fine-tuning or training a model.
- Growing the prompt to compensate for a weak model. Longer prompts are how the current results became hard to read; the answer is a smaller task.
- Inline completion and chat history.

## Open decisions for the maintainer

1. **Parser:** add JavaParser (a new pinned dependency, which `DependencyAuditTest` will check) or rely on LSP with a regex fallback.
2. **Verification:** in-process javac on changed files (fast, approximate classpath) or a Maven call (accurate, slower). A reasonable default is javac first with Maven as the final check.
3. **Whether to build the frontier provider at all now.** Without access it can only be tested with fakes. Options: build it behind the consent gate and label it unmeasured, or defer Phase 5's external provider until you can run it. A reasonable default is to defer it and spend the effort on the small profile, which you can measure. If you build it, choose the provider you would actually use, and decide whether frontier planning may see file bodies or only signatures. (Phase 5 built it behind the consent gate and labelled it unmeasured, with the Anthropic Messages API as a stand-in: see its Status.)
4. **Git apply support** (requested 4 Nov 2026). Proposed: add it only as an output format for the frontier profile, after Phase 1. Search-replace is more reliable than unified diffs for small models.
5. **Relation to ROADMAP-AI-NEXT.md:** confirm where that file lives. It did not appear in the root listing of the main branch when this roadmap was written.
