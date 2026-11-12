# AI edit baseline: qwen3.5:9b-q4_K_M, 2026-11-11

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-q4_K_M -Deval.editBlockMinLines=0 -Deval.retries=2`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, each proposal compiled in memory and, when that failed, repaired with the compiler's messages up to 2 more requests (Phase 2). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 20 of 24 | 83% |
| 2 | 20 of 24 | 83% |
| 3 | 20 of 24 | 83% |

Mean 83%, lowest 83%, highest 83%, standard deviation 0.0 points. The proposed target for the small profile is 70% within two attempts. Compare with the single-attempt baseline of the same model and profile.

Reached review with a compiling result: 72 of 72 results (100%).

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 100% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 100% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 100% |
| ADD_TESTS | 25% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| TEST_FAILURE | 12 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

No task changed outcome between runs.

## Never passed

fix-bug-null-name, tests-cart-total, tests-fizzbuzz, tests-slugify.

## Cost

- Run 1: 1213 s of model and build time, 27809 tokens reported by the provider
- Run 2: 1467 s of model and build time, 27809 tokens reported by the provider
- Run 3: 1334 s of model and build time, 27809 tokens reported by the provider

The raw per-task results are in the `results-2026-11-11.json` the run wrote to `target/eval/`.
