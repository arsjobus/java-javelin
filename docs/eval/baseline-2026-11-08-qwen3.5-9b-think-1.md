# AI edit baseline: qwen3.5:9b-mlx, 2026-11-08

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 16 of 24 | 67% |
| 2 | 15 of 24 | 63% |
| 3 | 15 of 24 | 63% |

Mean 64%, lowest 63%, highest 67%, standard deviation 2.4 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 33% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 67% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 75% |
| ADD_TESTS | 25% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 11 | The changed code does not compile. |
| TEST_FAILURE | 15 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

Passed in some runs, failed in others: add-method-peek.

## Never passed

add-method-money-plus, add-method-word-count, extract-format-line, fix-bug-null-name, sig-return-optional, tests-cart-total, tests-fizzbuzz, tests-slugify.

## Cost

- Run 1: 1049 s of model and build time, 26157 tokens reported by the provider
- Run 2: 1113 s of model and build time, 24706 tokens reported by the provider
- Run 3: 1165 s of model and build time, 24706 tokens reported by the provider

The raw per-task results are in the `results-2026-11-08.json` the run wrote to `target/eval/`.
