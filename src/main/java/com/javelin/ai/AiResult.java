package com.javelin.ai;

/**
 * An answer: the model's text, and for actions that propose a change the proposal if the reply held a usable one.
 * {@code note} says why there is no proposal (or what to be careful about), or is null.
 */
public record AiResult(String text, AiProposal proposal, String note) {}
