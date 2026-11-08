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

## Phase 4: Retrieval built for small windows

**Goal:** the model sees the code that matters, in as few tokens as possible.

Deliverables:
- A symbol index for the project: classes, method signatures, fields, imports, and call sites. The index uses the language server when one is running (v0.5 LSP), and a lightweight parser otherwise. Decision for the maintainer: adding JavaParser versus relying on LSP plus a regex fallback (see open decisions).
- Context per request is built from the target method body, the signatures of the types it uses, and the signatures of its direct callers. Each is included only if the profile's budget allows.
- Token counts become real for the model in use. The current estimator (no tokenizer bundled) is calibrated against the `prompt_eval_count` Ollama reports after a call. Frontier providers report their own counts.
- Smart Context selects at symbol level, and the preview says which symbols were chosen and why.

Tests: `SymbolIndexTest` over a fixture project; `ContextBudgetTest` per profile; a test that the preview lists every symbol that was sent.

Exit: context size on the corpus falls by a measured amount with no drop in success rate.

## Phase 5: Model profiles and the frontier provider

**Goal:** a frontier model runs through the same pipeline, with the same checks, and is never used without consent.

Deliverables:
- Profile resolution as described above, with a settings override.
- An external `AiProvider` for one frontier API. Before its first use in a project, the Send dialog names the provider, the destination host, and exactly what will be sent. Nothing is enabled by default, and the key is stored as PRIVACY.md describes.
- `OfflineGuardTest` is updated so that the single approved external client is the only network-capable class allowed, and only while the external provider is selected. The test still fails if any other main-code class touches the network.

Tests: `ProviderContractTest` run against the Ollama provider and a frontier fake; `ConsentGateTest` (no request without consent, consent is per project); updated `OfflineGuardTest`.

Exit: the frontier provider passes its contract and consent tests with fakes and recorded responses, and the small profile's results are unchanged by the refactor (the Phase 0 corpus reruns with the same numbers). No side-by-side quality comparison is claimed until frontier access exists.

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
3. **Whether to build the frontier provider at all now.** Without access it can only be tested with fakes. Options: build it behind the consent gate and label it unmeasured, or defer Phase 5's external provider until you can run it. A reasonable default is to defer it and spend the effort on the small profile, which you can measure. If you build it, choose the provider you would actually use, and decide whether frontier planning may see file bodies or only signatures.
4. **Git apply support** (requested 4 Nov 2026). Proposed: add it only as an output format for the frontier profile, after Phase 1. Search-replace is more reliable than unified diffs for small models.
5. **Relation to ROADMAP-AI-NEXT.md:** confirm where that file lives. It did not appear in the root listing of the main branch when this roadmap was written.
