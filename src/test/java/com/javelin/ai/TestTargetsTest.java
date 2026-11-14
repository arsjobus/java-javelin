package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the request for tests says about the code, read from the code: the limits and divisors that
 * decide a result, the documented sentences, the exceptions thrown on purpose, and the exact
 * package and class name.
 */
class TestTargetsTest {
  @TempDir Path dir;

  private static final String CART =
      """
      package cart;

      public class Cart {
        public void add(long priceCents) {
          if (priceCents < 0) throw new IllegalArgumentException("negative price");
        }

        /** The sum of the prices; carts of five or more items get 5% off the sum. */
        public long total() {
          if (pricesCents.size() >= 5) sum -= sum / 20; // five or more
          return sum;
        }
      }
      """;

  private static final String FIZZ =
      """
      package games;

      public final class FizzBuzz {
        public static String of(int n) {
          if (n % 15 == 0) return "FizzBuzz";
          if (n % 3 == 0) return "Fizz";
          if (n % 5 == 0) return "Buzz";
          return Integer.toString(n);
        }
      }
      """;

  @Test
  void aLimitIsTestedBelowAtAndAboveIt() {
    List<String> boundaries = TestTargets.boundaries(CART);
    assertTrue(
        boundaries.stream().anyMatch(b -> b.contains(">= 5") && b.contains("test 4, 5 and 6")),
        boundaries.toString());
    assertTrue(boundaries.stream().anyMatch(b -> b.contains("test -1, 0 and 1")));
  }

  @Test
  void aDivisorIsTestedWithItsMultiplesAndCommonMultiples() {
    List<String> boundaries = TestTargets.boundaries(FIZZ);
    assertEquals(1, boundaries.size(), boundaries.toString());
    assertTrue(boundaries.get(0).contains("15, 3, 5"));
    assertTrue(boundaries.get(0).contains("Good inputs: 15, 3, 5"));
  }

  @Test
  void theZeroOfAModuloComparisonIsNotALimit() {
    assertTrue(
        TestTargets.boundaries("if (n % 3 == 0) return a;").stream()
            .noneMatch(b -> b.contains("== 0")));
  }

  @Test
  void commonMultiplesOfDifferentDivisorsAreAdded() {
    String src = "if (n % 4 == 0) a(); if (n % 6 == 0) b();";
    assertTrue(TestTargets.boundaries(src).get(0).contains("4, 6, 12"));
  }

  @Test
  void numbersInCommentsAndStringsAreNotLimits() {
    String src = "// if (x > 100) then\n/** limit is > 7 */\nString s = \"a > 3\";\n";
    assertTrue(TestTargets.boundaries(src).isEmpty());
  }

  @Test
  void theDocumentedSentencesAreListed() {
    List<String> docs = TestTargets.documented(CART);
    assertEquals(
        List.of("The sum of the prices; carts of five or more items get 5% off the sum."), docs);
  }

  @Test
  void docTagsAndMarkupAreLeftOut() {
    String src =
        "/**\n * Returns {@code x} <b>doubled</b>.\n * @param x a number\n */\nint f(int x)";
    assertEquals(List.of("Returns x doubled."), TestTargets.documented(src));
  }

  @Test
  void aThrowAppearsWithItsLine() {
    List<String> thrown = TestTargets.thrown(CART);
    assertEquals(1, thrown.size());
    assertTrue(thrown.get(0).startsWith("`IllegalArgumentException`"));
    assertTrue(thrown.get(0).contains("priceCents < 0"));
  }

  @Test
  void thePackageAndClassNameAreStated() {
    assertEquals("cart", TestTargets.packageOf(CART));
    assertEquals("Cart", TestTargets.typeOf(CART));
    String hints = TestTargets.hints(CART, "Cart.java", "java");
    assertTrue(hints.contains("`package cart;`"));
    assertTrue(hints.contains("class named `CartTest`"));
    assertTrue(hints.contains("JUnit 5 only"));
  }

  @Test
  void aClassWithoutAPackageIsSaidToHaveNone() {
    String hints = TestTargets.hints("public class A {}\n", "A.java", "java");
    assertTrue(hints.contains("no package"));
    assertTrue(hints.contains("`ATest`"));
  }

  @Test
  void codeThatGivesNothingToPointAtGivesNoHints() {
    assertEquals("", TestTargets.hints("x = 1\nprint(x)\n", "a.py", "python"));
  }

  @Test
  void otherLanguagesGetTheLimitsButNoJavaNaming() {
    String hints = TestTargets.hints("if (count >= 3) { go(); }\n", "a.js", "javascript");
    assertTrue(hints.contains("test 2, 3 and 4"));
    assertFalse(hints.contains("JUnit"));
  }

  @Test
  void thePromptForTestsCarriesTheHintsAndTheRules() throws Exception {
    FakeAiProvider provider = new FakeAiProvider();
    Settings settings = new Settings(dir.resolve("settings.conf"));
    settings.put(Scope.USER, "ai.model", "fake-model");
    AiService service = new AiService(provider, AiConfig.from(settings));
    AiSource source = new AiSource("src/Cart.java", "Cart.java", "java", CART, 0, 0);
    AiPlan plan = service.prepare(AiAction.GENERATE_TESTS, source, null);
    String prompt = plan.request().prompt();
    assertTrue(prompt.contains("test 4, 5 and 6"), prompt);
    assertTrue(prompt.contains("`package cart;`"));
    assertTrue(prompt.contains("test a limit at the limit itself"));
    assertTrue(prompt.contains("do not test inputs the code says nothing about"));
  }

  @Test
  void otherActionsDoNotGetTheTestHints() throws Exception {
    FakeAiProvider provider = new FakeAiProvider();
    Settings settings = new Settings(dir.resolve("settings2.conf"));
    settings.put(Scope.USER, "ai.model", "fake-model");
    AiService service = new AiService(provider, AiConfig.from(settings));
    AiSource source = new AiSource("src/Cart.java", "Cart.java", "java", CART, 0, CART.length());
    String prompt = service.prepare(AiAction.DEBUG, source, null).request().prompt();
    assertFalse(prompt.contains("test 4, 5 and 6"));
  }
}
