package com.javelin.ai;

/**
 * How a model is asked to choose its words. The editor always uses {@link #PRODUCT}; the eval
 * harness asks for {@link #deterministic} so that a run can be repeated. {@code seed} is null when
 * the provider should pick its own.
 */
public record Sampling(double temperature, Long seed) {
  /** What the editor has always asked for. */
  public static final Sampling PRODUCT = new Sampling(0.2, null);

  /** Temperature 0 and a fixed seed, for repeatable runs where the model supports it. */
  public static Sampling deterministic(long seed) {
    return new Sampling(0, seed);
  }
}
