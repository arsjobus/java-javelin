package com.javelin.ai;

/**
 * The tokens a provider says a request used. Not every provider says; {@link #UNKNOWN} then. The
 * numbers are the provider's own count, not the editor's estimate.
 */
public record AiUsage(int promptTokens, int completionTokens) {
  public static final AiUsage UNKNOWN = new AiUsage(-1, -1);

  public boolean isKnown() {
    return promptTokens >= 0 && completionTokens >= 0;
  }

  public int total() {
    return isKnown() ? promptTokens + completionTokens : -1;
  }
}
