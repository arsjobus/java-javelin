# AI edit baseline: qwen2.5-coder:7b, 2026-11-09

Measured with `mvn -Peval test -Deval.model=qwen2.5-coder:7b`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, each proposal compiled in memory and, when that failed, repaired with the compiler's messages up to 2 more requests (Phase 2). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 17 of 24 | 71% |
| 2 | 17 of 24 | 71% |
| 3 | 17 of 24 | 71% |

Mean 71%, lowest 71%, highest 71%, standard deviation 0.0 points. The proposed target for the small profile is 70% within two attempts. Compare with the single-attempt baseline of the same model and profile.

Reached review with a compiling result: 66 of 72 results (92%).

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 75% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 100% |
| RENAME_ACROSS_FILES | 75% |
| CHANGE_SIGNATURE | 50% |
| ADD_TESTS | 50% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 6 | The changed code does not compile. |
| TEST_FAILURE | 15 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

No task changed outcome between runs.

## Never passed

add-method-word-count, fix-bug-null-name, rename-incr, sig-add-parameter, sig-return-optional, tests-cart-total, tests-fizzbuzz.

## Cost

- Run 1: 468 s of model and build time, 30906 tokens reported by the provider
- Run 2: 515 s of model and build time, 30906 tokens reported by the provider
- Run 3: 628 s of model and build time, 30906 tokens reported by the provider

The raw per-task results are in the `results-2026-11-09.json` the run wrote to `target/eval/`.
