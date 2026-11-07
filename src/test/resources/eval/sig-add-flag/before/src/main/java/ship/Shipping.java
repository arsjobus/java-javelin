package ship;

public final class Shipping {
  private Shipping() {}

  /** Cents to ship a parcel: 300 plus 1 cent per 10 grams. */
  public static long cost(int grams) {
    return 300 + grams / 10;
  }
}
