# AI edit baseline: qwen3.5:9b-mlx, 2026-11-08

Measured with `mvn -Peval test -Deval.model=qwen3.5:9b-mlx -Deval.think=none`. Profile `small`: the editor's own limits and window, temperature 0, seed 42 plus the run number. 24 tasks, 3 runs, one attempt per task (no retries exist yet). A task passes when its judging test passes after the change is applied; nothing is judged by a model.

## Result

| Run | Passed | Rate |
| --- | --- | --- |
| 1 | 14 of 24 | 58% |
| 2 | 13 of 24 | 54% |
| 3 | 13 of 24 | 54% |

Mean 56%, lowest 54%, highest 58%, standard deviation 2.4 points. The proposed target for the small profile is 70% within two attempts; this baseline is single-attempt.

## By kind of task

| Kind | Mean pass rate |
| --- | --- |
| ADD_METHOD | 33% |
| FIX_BUG | 80% |
| EXTRACT_METHOD | 67% |
| RENAME_ACROSS_FILES | 100% |
| CHANGE_SIGNATURE | 50% |
| ADD_TESTS | 0% |

## Why tasks failed

| Reason | Count (all runs) | Meaning |
| --- | --- | --- |
| NO_EDIT_BLOCK | 8 | The reply holds no change that can be applied. |
| COMPILE_ERROR | 9 | The changed code does not compile. |
| TEST_FAILURE | 15 | The changed code compiles but its tests fail. |

Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 0 of 72 results.

## Where luck decides

Passed in some runs, failed in others: add-method-money-plus.

## Never passed

add-method-clamp, add-method-word-count, extract-leap-year, fix-bug-null-name, sig-add-parameter, sig-return-optional, tests-cart-total, tests-fizzbuzz, tests-roman, tests-slugify.

## Cost

- Run 1: 810 s of model and build time, 20778 tokens reported by the provider
- Run 2: 748 s of model and build time, 20682 tokens reported by the provider
- Run 3: 735 s of model and build time, 20461 tokens reported by the provider

The raw per-task results are in the `results-2026-11-08.json` the run wrote to `target/eval/`.
