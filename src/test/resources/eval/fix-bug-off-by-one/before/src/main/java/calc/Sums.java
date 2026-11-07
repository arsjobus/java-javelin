package calc;

public final class Sums {
  private Sums() {}

  public static int sumUpTo(int n) {
    int total = 0;
    for (int i = 1; i < n; i++) {
      total += i;
    }
    return total;
  }

  public static int sumOf(int... values) {
    int total = 0;
    for (int v : values) total += v;
    return total;
  }
}
