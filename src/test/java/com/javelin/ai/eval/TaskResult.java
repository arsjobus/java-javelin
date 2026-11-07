package com.javelin.ai.eval;

import com.javelin.ai.FailureReason;

/**
 * What happened to one task in one run. {@code applied}: a change came out of the pipeline and was
 * written to the task's copy of the project. {@code compiled}: that copy compiled. {@code passed}:
 * the judging tests passed (for ADD_TESTS: the model's tests pass on the code and catch the planted
 * bug). {@code failure} is null exactly when {@code passed}. Token counts are -1 when the provider
 * did not report them. {@code flagged} says the editor's own checks (the ones the review shows
 * before "Apply anyway") found an error in the proposal, so a later phase can see whether they
 * predict failure.
 */
record TaskResult(
    String id,
    EvalTask.Category category,
    boolean applied,
    boolean compiled,
    boolean passed,
    int attempts,
    long millis,
    long promptTokens,
    long completionTokens,
    int changedLines,
    boolean flagged,
    FailureReason failure,
    String detail) {}
