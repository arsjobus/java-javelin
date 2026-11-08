# AI edit baseline: qwen2.5-coder:7b, 2026-11-08

Measured with `mvn -Peval test -Deval.model=qwen2.5-coder:7b -Deval.editBlockMinLines=0`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 13 of 24 | 54% |
| 2 | 13 of 24 | 54% |
| 3 | 13 of 24 | 54% |

Mean 54%, lowest 54%, highest 54%, standard deviation 0.0 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 50% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 100% |
| RENAME_ACROSS_FILES | 0% |
| CHANGE_SIGNATURE | 50% |
| ADD_TESTS | 50% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| COMPILE_ERROR | 18 | The changed code does not compile. |
| TEST_FAILURE | 15 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

No task changed outcome between runs.

## Never passed

add-method-peek, add-method-word-count, fix-bug-null-name, rename-enum-constant, rename-fetch-all, rename-incr, rename-interface-method, sig-add-parameter, sig-return-optional, tests-cart-total, tests-fizzbuzz.

## Cost

- Run 1: 464 s of model and build time, 17935 tokens reported by the provider
- Run 2: 558 s of model and build time, 17935 tokens reported by the provider
- Run 3: 621 s of model and build time, 17935 tokens reported by the provider

The raw per-task results are in the `results-2026-11-08.json` the run wrote to `target/eval/`.
