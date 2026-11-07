package games;

public final class FizzBuzz {
  private FizzBuzz() {}

  /** "Fizz" for multiples of 3, "Buzz" for multiples of 5, "FizzBuzz" for both, else the number. */
  public static String of(int n) {
    if (n % 15 == 0) return "FizzBuzz";
    if (n % 3 == 0) return "Fizz";
    if (n % 5 == 0) return "Buzz";
    return Integer.toString(n);
  }
}
