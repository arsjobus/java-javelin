package util;

public final class Ints {
  private Ints() {}

  public static int max(int a, int b) {
    return a > b ? a : b;
  }

  public static boolean isEven(int value) {
    return value % 2 == 0;
  }

  public static int clamp(int value, int low, int high) {
    if (low > high) throw new IllegalArgumentException("low > high");
    return Math.max(low, Math.min(high, value));
  }
}
