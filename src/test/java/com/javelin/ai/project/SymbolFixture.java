package com.javelin.ai.project;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A small shop project whose classes are long enough that a view of them is smaller than the file:
 * a price calculator (the code a request is about), a checkout that calls it, a report class that
 * has nothing to do with it, a small value class, and a test.
 */
final class SymbolFixture {
  private SymbolFixture() {}

  /** {@code count} short methods {@code <prefix>1() ... <prefix>count()}, each with a few lines. */
  static String filler(String prefix, int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 1; i <= count; i++)
      out.append("  int ")
          .append(prefix)
          .append(i)
          .append("(int x) {\n")
          .append("    int a = x * ")
          .append(i)
          .append(";\n")
          .append("    int b = a + ")
          .append(i * 7)
          .append(";\n")
          .append("    return a > b ? a - b : b - a;\n")
          .append("  }\n\n");
    return out.toString();
  }

  static Path shop(Path root) throws IOException {
    ProjectFixture.write(root, "README.md", "# Shop\n\nPrices, discounts and checkout.\n");
    ProjectFixture.write(
        root,
        "src/main/java/shop/Money.java",
        "package shop;\n\n"
            + "public record Money(long cents) {\n"
            + "  public Money times(int percent) {\n"
            + "    return new Money(cents * percent / 100);\n"
            + "  }\n"
            + "}\n");
    ProjectFixture.write(
        root,
        "src/main/java/shop/PriceCalculator.java",
        "package shop;\n\n"
            + "import java.util.List;\n\n"
            + "/** Works out what a basket costs. */\n"
            + "public class PriceCalculator {\n"
            + "  private final int taxPercent;\n"
            + "  private final List<String> log = new java.util.ArrayList<>();\n\n"
            + "  public PriceCalculator(int taxPercent) {\n"
            + "    this.taxPercent = taxPercent;\n"
            + "  }\n\n"
            + "  /** Takes a percentage off a price. */\n"
            + "  public Money applyDiscount(Money price, int percent) {\n"
            + "    if (percent < 0 || percent > 100) {\n"
            + "      throw new IllegalArgumentException(\"percent\");\n"
            + "    }\n"
            + "    log.add(\"discount \" + percent);\n"
            + "    return new Money(price.cents() - price.times(percent).cents());\n"
            + "  }\n\n"
            + "  public Money withTax(Money price) {\n"
            + "    return new Money(price.cents() + price.times(taxPercent).cents());\n"
            + "  }\n\n"
            + filler("rounding", 14)
            + "}\n");
    ProjectFixture.write(
        root,
        "src/main/java/shop/Checkout.java",
        "package shop;\n\n"
            + "public class Checkout {\n"
            + "  private final PriceCalculator calculator = new PriceCalculator(20);\n\n"
            + "  public Money total(Money subtotal, int loyaltyPercent) {\n"
            + "    Money discounted = calculator.applyDiscount(subtotal, loyaltyPercent);\n"
            + "    return calculator.withTax(discounted);\n"
            + "  }\n\n"
            + "  public String receipt(Money total) {\n"
            + "    return \"Total: \" + total.cents();\n"
            + "  }\n\n"
            + filler("packing", 14)
            + "}\n");
    ProjectFixture.write(
        root,
        "src/main/java/shop/Reports.java",
        "package shop;\n\n"
            + "public class Reports {\n"
            + "  public String monthly() {\n"
            + "    return \"monthly\";\n"
            + "  }\n\n"
            + filler("figure", 16)
            + "}\n");
    ProjectFixture.write(
        root,
        "src/test/java/shop/PriceCalculatorTest.java",
        "package shop;\n\n"
            + "import static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
            + "import org.junit.jupiter.api.Test;\n\n"
            + "class PriceCalculatorTest {\n"
            + "  @Test\n"
            + "  void tenPercentOff() {\n"
            + "    PriceCalculator c = new PriceCalculator(0);\n"
            + "    assertEquals(900, c.applyDiscount(new Money(1000), 10).cents());\n"
            + "  }\n"
            + "}\n");
    return root;
  }
}
