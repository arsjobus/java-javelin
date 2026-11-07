package shop;

public final class Pricing {
  public static final long DISCOUNT_FROM_CENTS = 10_000;

  private Pricing() {}

  /** The total after the 10% discount for orders of 100.00 or more. */
  public static long discounted(long totalCents) {
    if (totalCents > DISCOUNT_FROM_CENTS) {
      return totalCents - totalCents / 10;
    }
    return totalCents;
  }
}
