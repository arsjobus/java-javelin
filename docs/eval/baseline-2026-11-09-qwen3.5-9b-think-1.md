# AI edit baseline: qwen3.5:9b-mlx, 2026-11-09

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx -Deval.editBlockMinLines=0 -Deval.retries=2`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, each proposal compiled in memory and, when that failed, repaired with the compiler's messages up to 2 more requests (Phase 2). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 20 of 24 | 83% |
| 2 | 21 of 24 | 88% |
| 3 | 19 of 24 | 79% |

Mean 83%, lowest 79%, highest 88%, standard deviation 4.2 points. The proposed target for the small profile is 70% within two attempts. Compare with the single-attempt baseline of the same model and profile.

Reached review with a compiling result: 71 of 72 results (99%).

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 100% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 100% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 92% |
| ADD_TESTS | 33% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 1 | The changed code does not compile. |
| TEST_FAILURE | 11 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

Passed in some runs, failed in others: sig-return-optional, tests-slugify.

## Never passed

fix-bug-null-name, tests-cart-total, tests-fizzbuzz.

## Cost

- Run 1: 1209 s of model and build time, 30702 tokens reported by the provider
- Run 2: 1162 s of model and build time, 30081 tokens reported by the provider
- Run 3: 1176 s of model and build time, 34011 tokens reported by the provider

The raw per-task results are in the `results-2026-11-09.json` the run wrote to `target/eval/`.
