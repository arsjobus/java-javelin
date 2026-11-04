package com.javelin.ai;

/** What a provider answered. {@code truncated} is true when the model stopped because it ran out of room, not because it was done. */
public record AiReply(String text, boolean truncated) {}
