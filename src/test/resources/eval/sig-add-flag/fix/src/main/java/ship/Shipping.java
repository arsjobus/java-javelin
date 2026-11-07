package ship;

public final class Shipping {
  private Shipping() {}

  /** Cents to ship a parcel: 300 plus 1 cent per 10 grams, doubled for express. */
  public static long cost(int grams, boolean express) {
    long normal = 300 + grams / 10;
    return express ? normal * 2 : normal;
  }
}
