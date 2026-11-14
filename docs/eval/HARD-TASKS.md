# Tasks that rarely pass

Read from the baselines of 2026-11-07 to 2026-11-11 (`qwen3.5:9b`, `qwen2.5-coder:7b`). Four tasks never passed in the latest runs: `fix-bug-null-name`, `tests-cart-total`, `tests-fizzbuzz`, `tests-slugify`. The other 20 pass every run with the verify-and-retry loop, so these four are what is left of the 83%.

| Task | What the evidence shows | What was changed |
| --- | --- | --- |
| `fix-bug-null-name` | The request said a null or blank name returns `"Hello, stranger!"`, but the judging test also needs `new Greeter("Hi").greet("   ")` to return `"Hi, stranger!"`. A model that does what the request says hardcodes the prefix and fails. The reference change uses the prefix. This is a defect in the task, not in the pipeline. | The request now says "with the greeter's own prefix" and gives both examples. Results of this task are not comparable with earlier baselines. |
| `tests-*` (3 tasks) | Every failure is `TEST_FAILURE`: either the tests fail on the real code, or they pass on the code with the planted bug. The planted bugs sit on exact values (`> 5` for `>= 5`, a missing `% 15` case, a missing trim of dashes). The prompt only asked for "normal cases, edge cases and failures", so the model had no reason to test exactly 5 or 15, and "failures" invited assertions the code never makes. The baselines did not record which of the two happened. | `TestTargets` reads the code and adds to the prompt: the exact package and class name, each documented sentence, each numeric limit (below, at, above), each divisor with common multiples, and exceptions thrown on purpose. The prompt also tells the model to assert only what the code states and to test a limit at the limit. |

The baseline now has a section "How the never-passed tasks failed" (reason and first line of the detail), so the next run shows which of the two cases it was.

Not measured: these changes were tested with scripted replies, not with a model. Run `mvn -Peval test -Deval.model=... -Deval.retries=2` and compare with `baseline-2026-11-11-qwen3.5-9b-think-2.md`. If a `tests-*` task still fails, read its detail first: "fail on the real code" means the expectation is wrong (look at the hints), "also on the planted bug" means a value is still missing.
