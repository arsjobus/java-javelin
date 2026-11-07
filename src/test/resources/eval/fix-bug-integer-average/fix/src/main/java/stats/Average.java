package stats;

public final class Average {
  private Average() {}

  public static double average(int[] values) {
    if (values.length == 0) return 0.0;
    int sum = 0;
    for (int v : values) sum += v;
    return (double) sum / values.length;
  }
}
