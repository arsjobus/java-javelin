package com.javelin.ai;

/**
 * What a provider answered. {@code truncated} is true when the model stopped because it ran out of
 * room, not because it was done. {@code usage} is the provider's own token count, or {@link
 * AiUsage#UNKNOWN}.
 */
public record AiReply(String text, boolean truncated, AiUsage usage) {
  public AiReply {
    if (usage == null) usage = AiUsage.UNKNOWN;
  }

  public AiReply(String text, boolean truncated) {
    this(text, truncated, AiUsage.UNKNOWN);
  }
}
