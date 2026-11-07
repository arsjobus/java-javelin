package util;

public final class Ints {
  private Ints() {}

  public static int max(int a, int b) {
    return a > b ? a : b;
  }

  public static boolean isEven(int value) {
    return value % 2 == 0;
  }
}
