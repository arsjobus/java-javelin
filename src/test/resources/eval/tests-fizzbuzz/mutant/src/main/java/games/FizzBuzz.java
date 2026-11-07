package games;

public final class FizzBuzz {
  private FizzBuzz() {}

  public static String of(int n) {
    if (n % 3 == 0) return "Fizz";
    if (n % 5 == 0) return "Buzz";
    if (n % 15 == 0) return "FizzBuzz";
    return Integer.toString(n);
  }
}
