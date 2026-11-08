# AI edit baseline: qwen3.5:9b-mlx, 2026-11-08

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 14 of 24 | 58% |
| 2 | 12 of 24 | 50% |
| 3 | 13 of 24 | 54% |

Mean 54%, lowest 50%, highest 58%, standard deviation 4.2 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 75% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 22% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 33% |
| ADD_TESTS | 0% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| NO_EDIT_BLOCK | 1 | The reply holds no change that can be applied. |
| COMPILE_ERROR | 16 | The changed code does not compile. |
| TEST_FAILURE | 16 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

Passed in some runs, failed in others: extract-validate-age, sig-swap-parameters.

## Never passed

add-method-word-count, extract-format-line, extract-leap-year, fix-bug-null-name, sig-add-parameter, sig-return-optional, tests-cart-total, tests-fizzbuzz, tests-roman, tests-slugify.

## Cost

- Run 1: 530 s of model and build time, 19558 tokens reported by the provider
- Run 2: 498 s of model and build time, 19659 tokens reported by the provider
- Run 3: 539 s of model and build time, 19414 tokens reported by the provider

The raw per-task results are in the `results-2026-11-08.json` the run wrote to `target/eval/`.
