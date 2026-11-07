package numbers;

public final class Roman {
  private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
  private static final String[] SYMBOLS = {
    "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"
  };

  private Roman() {}

  /** The Roman numeral for 1..3999. */
  public static String toRoman(int number) {
    if (number < 1 || number > 3999) throw new IllegalArgumentException("out of range");
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < VALUES.length; i++) {
      while (number >= VALUES[i]) {
        out.append(SYMBOLS[i]);
        number -= VALUES[i];
      }
    }
    return out.toString();
  }
}
