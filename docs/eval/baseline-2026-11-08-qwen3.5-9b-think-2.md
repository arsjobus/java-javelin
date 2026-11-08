# AI edit baseline: qwen3.5:9b-mlx, 2026-11-08

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx -Deval.editBlockMinLines=0`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 16 of 24 | 67% |
| 2 | 18 of 24 | 75% |
| 3 | 16 of 24 | 67% |

Mean 69%, lowest 67%, highest 75%, standard deviation 4.8 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 83% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 44% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 75% |
| ADD_TESTS | 25% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 8 | The changed code does not compile. |
| TEST_FAILURE | 14 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

Passed in some runs, failed in others: add-method-word-count, extract-leap-year.

## Never passed

extract-format-line, fix-bug-null-name, sig-return-optional, tests-cart-total, tests-fizzbuzz, tests-slugify.

## Cost

- Run 1: 1063 s of model and build time, 24858 tokens reported by the provider
- Run 2: 1128 s of model and build time, 25511 tokens reported by the provider
- Run 3: 1079 s of model and build time, 24831 tokens reported by the provider

The raw per-task results are in the `results-2026-11-08.json` the run wrote to `target/eval/`.
