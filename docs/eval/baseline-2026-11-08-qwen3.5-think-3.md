# AI edit baseline: qwen3.5:9b-mlx, 2026-11-08

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx -Deval.editBlockMinLines=0`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 18 of 24 | 75% |
| 2 | 20 of 24 | 83% |
| 3 | 18 of 24 | 75% |

Mean 78%, lowest 75%, highest 83%, standard deviation 4.8 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 100% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 78% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 75% |
| ADD_TESTS | 33% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 5 | The changed code does not compile. |
| TEST_FAILURE | 11 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

Passed in some runs, failed in others: extract-format-line, extract-leap-year, tests-slugify.

## Never passed

fix-bug-null-name, sig-return-optional, tests-cart-total, tests-fizzbuzz.

## Cost

- Run 1: 941 s of model and build time, 26135 tokens reported by the provider
- Run 2: 1127 s of model and build time, 26487 tokens reported by the provider
- Run 3: 1539 s of model and build time, 28529 tokens reported by the provider

The raw per-task results are in the `results-2026-11-08.json` the run wrote to `target/eval/`.
