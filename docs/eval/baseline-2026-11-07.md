# AI edit baseline: qwen3.5:9b-mlx, 2026-11-07

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 15 of 24 | 63% |
| 2 | 16 of 24 | 67% |
| 3 | 16 of 24 | 67% |

Mean 65%, lowest 63%, highest 67%, standard deviation 2.4 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 25% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 78% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 75% |
| ADD_TESTS | 33% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 11 | The changed code does not compile. |
| TEST_FAILURE | 14 | The changed code compiles but its tests fail. |

## Where luck decides

Passed in some runs, failed in others: extract-format-line, tests-slugify.

## Never passed

add-method-money-plus, add-method-peek, add-method-word-count, fix-bug-null-name, sig-return-optional, tests-cart-total, tests-fizzbuzz.

## Cost

- Run 1: 1075 s of model and build time, 25249 tokens reported by the provider
- Run 2: 1087 s of model and build time, 25569 tokens reported by the provider
- Run 3: 1164 s of model and build time, 27703 tokens reported by the provider

The raw per-task results are in the `results-2026-11-07.json` the run wrote to `target/eval/`.
