# AI edit evaluation

How well does a model change code through Javelin, measured the same way every time? This folder holds the measurements; the harness is described here. It is Phase 0 of [ROADMAP-AI-RELIABILITY.md](../../ROADMAP-AI-RELIABILITY.md).

## Run it

Ollama must be running with the model installed (`ollama pull qwen3.5:9b`). Then, from the project root:

```
mvn -Peval test -Deval.model=qwen3.5:9b
```

Only the tests tagged `eval` run (`mvn test` never runs them, and never calls a model). Options, all optional:

| Option | Default | Meaning |
| --- | --- | --- |
| `-Deval.runs=3` | 3 | how many times the whole corpus is run, to show variance |
| `-Deval.seed=42` | 42 | run *n* (from 0) asks for seed `seed + n`, with temperature 0 |
| `-Deval.tasks=<regex>` | all | only tasks whose id matches, e.g. `-Deval.tasks=rename` |
| `-Deval.profile=small` | `small` | the only profile in Phase 0; any other name is an error |
| `-Deval.endpoint=...` | `http://127.0.0.1:11434` | must be a loopback address, as everywhere in Javelin |
| `-Deval.think=...` | the product default | `none`, `low`, `medium` or `high` |
| `-Deval.timeoutSeconds=...` | the product default | how long to wait for one answer |

It writes two files to `target/eval/`: `results-<date>.json` (every task of every run) and `baseline-<date>.md` (the readable summary). To publish a baseline, copy the Markdown to `docs/eval/baseline-<date>.md` and commit it. If Ollama is not running or the model is not installed the run stops with that message instead of recording a row of failures.

## What is measured

The `small` profile is the editor as it is: its own limits, window and prompts, with only the sampling changed to temperature 0 and a fixed seed so a run can be repeated. The corpus is 24 small projects under `src/test/resources/eval/`:

| Kind | Tasks | Goes through |
| --- | --- | --- |
| add a method | 4 | Propose Change on a selection |
| fix a bug | 5 | Propose Change on a selection |
| extract a method | 3 | Propose Change on a selection |
| rename across files | 4 | a project request (Smart Context) |
| change a signature and its callers | 4 | a project request (Smart Context) |
| add tests for existing code | 4 | Generate Tests |

Each task is a folder:

```
<id>/task.properties   the request, the file, the selection, the judging tests
<id>/before/           the project as the model finds it (sources and the judging test)
<id>/fix/              the reference change: the files a correct change replaces or adds
<id>/mutant/           add-tests tasks only: the source with a bug planted in it
```

The pipeline's reply is applied the way the editor would apply it, the changed project is compiled with the JDK's compiler, and the tests are run in a separate JVM with a time limit (a model's code may loop or call `System.exit`). **Nothing is judged by a model or by reading the reply: a task passes when its judging test passes.**

For add-tests tasks there is no test to run, so the model's tests are judged: they must pass on the real code and fail on the copy with the planted bug.

A change that passes but is more than four times the reference change (at least 40 lines) is counted as `OVERSIZED`: the roadmap's aim is edits a person can review, and a rewrite of the whole method for a one-character fix is not one.

## Failure reasons

Every failed task has exactly one, from the shared `com.javelin.ai.FailureReason` (the editor's own checks use the same names): `NO_EDIT_BLOCK`, `UNPARSEABLE`, `ANCHOR_NOT_FOUND`, `WRONG_FILE`, `OVERSIZED`, `TRUNCATED`, `COMPILE_ERROR`, `TEST_FAILURE`, `REFUSED`, and `TIMEOUT` (the model did not answer in time: added because a small model that thinks for minutes is a real way to fail). `TEST_FAILURE` also covers generated tests that do not detect the planted bug.

## Reading a result

Each task records: `applied` (a change came out and was written), `compiled`, `passed`, `attempts` (always 1 until retries exist), `millis`, `promptTokens` and `completionTokens` (the provider's own count, `null` when it did not say), `changedLines`, `flagged` (the editor's own review checks found an error in the proposal, which lets a later phase ask whether they predict failure), the `failure` reason and a short `detail`.

The baseline reports the rate of each run, their mean, lowest, highest and standard deviation, the rate per kind of task, the failure counts per reason, the tasks whose outcome changed between runs (where luck decides) and the tasks that never passed.

## Checking the harness itself

`mvn test` checks the harness without a model:

- `EvalCorpusTest`: every task fails before its reference change and passes after it, a selection task's reference change is one the pipeline can express, and every planted bug is caught by the reference tests.
- `EvalRunnerTest`: a scripted provider that always knows the right answer passes all 24 tasks through the real pipeline, and one scripted provider per failure reason is counted under that reason.
- `FixtureBuildTest`, `EvalReportTest`, `EvalProfileTest`, `FailureReasonTest`.

## Baselines

None is committed yet: the numbers come from running the command above against a real model on a real machine.
