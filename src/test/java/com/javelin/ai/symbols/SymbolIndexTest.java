package com.javelin.ai.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.SourceOutline;
import com.javelin.ai.symbols.SymbolIndex.CallSite;
import com.javelin.ai.symbols.SymbolIndex.Symbol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SymbolIndexTest {
  private static final String MONEY = "package shop;\n\npublic record Money(long cents) {\n}\n";
  private static final String CALC =
      """
      package shop;

      import java.util.List;

      public class Calc {
        private int rate;

        public Calc(int rate) {
          this.rate = rate;
        }

        public Money discount(Money price, int percent) {
          return new Money(price.cents() - percent);
        }

        public Money twice(Money price) {
          return discount(discount(price, 1), 1);
        }
      }
      """;
  private static final String CHECKOUT =
      """
      package shop.web;

      import shop.Calc;

      public class Checkout {
        Calc calc = new Calc(3);

        long total(shop.Money m) {
          // discount(m, 5) is only a comment here
          return calc.discount(m, 5).cents();
        }

        long other() {
          return 0;
        }
      }
      """;
  private static final String UNRELATED =
      """
      package other;

      public class Elsewhere {
        int discount(int x) {
          return x;
        }

        int call() {
          return discount(1);
        }
      }
      """;

  private static SymbolIndex index() {
    Map<String, String> texts = new LinkedHashMap<>();
    texts.put("src/shop/Money.java", MONEY);
    texts.put("src/shop/Calc.java", CALC);
    texts.put("src/shop/web/Checkout.java", CHECKOUT);
    texts.put("src/other/Elsewhere.java", UNRELATED);
    return SymbolIndex.build(texts);
  }

  private static Symbol one(SymbolIndex index, String qualified) {
    return index.symbols().stream()
        .filter(s -> s.qualified().equals(qualified))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void itKnowsTheSymbolsPackagesAndImportsOfEveryFile() {
    SymbolIndex i = index();
    assertEquals("shop", i.packageOf("src/shop/Calc.java"));
    assertEquals(List.of("java.util.List"), i.importsOf("src/shop/Calc.java"));
    assertEquals(
        1,
        i.callablesNamed("discount").stream().filter(s -> s.path().endsWith("Calc.java")).count());
    assertEquals(SourceOutline.Kind.TYPE, one(i, "Calc").kind());
    assertEquals(SourceOutline.Kind.FIELD, one(i, "Calc.rate").kind());
    assertEquals(
        "public Money discount(Money price, int percent)",
        one(i, "Calc.discount").member().signature());
  }

  @Test
  void aBodyIsTheLinesOfTheMethodExactly() {
    SymbolIndex i = index();
    assertEquals(
        "  public Money discount(Money price, int percent) {\n"
            + "    return new Money(price.cents() - percent);\n"
            + "  }",
        i.body(one(i, "Calc.discount")));
  }

  @Test
  void theTypesAMethodUsesAreTheOtherFilesTypesItNames() {
    SymbolIndex i = index();
    List<Symbol> used = i.typesUsedBy(one(i, "Calc.discount"));
    assertEquals(List.of("Money"), used.stream().map(Symbol::name).toList());
    assertEquals("src/shop/Money.java", used.get(0).path());
    assertEquals(
        List.of(),
        i.typesUsedBy(one(i, "Checkout.other")),
        "a method that names no type uses none");
  }

  @Test
  void callersAreFoundByNameInFilesThatCouldSeeTheType() {
    SymbolIndex i = index();
    Symbol discount = one(i, "Calc.discount");
    List<CallSite> sites = i.callSitesOf(discount);
    List<String> callers = sites.stream().map(s -> s.caller().qualified()).toList();
    assertTrue(callers.contains("Calc.twice"), callers.toString());
    assertTrue(callers.contains("Checkout.total"), callers.toString());
    assertEquals(
        2, callers.size(), "a method of another package that never names Calc is not a caller");
  }

  @Test
  void aCallInACommentIsNotACallAndTheDeclarationItselfIsNotACaller() {
    SymbolIndex i = index();
    CallSite site =
        i.callSitesOf(one(i, "Calc.discount")).stream()
            .filter(s -> s.caller().qualified().equals("Checkout.total"))
            .findFirst()
            .orElseThrow();
    assertEquals(10, site.line(), "the line of calc.discount(, not the comment above it");
    assertTrue(i.callSitesOf(one(i, "Checkout.other")).isEmpty());
  }

  @Test
  void aConstructorIsCalledByNew() {
    SymbolIndex i = index();
    List<String> callers =
        i.callSitesOf(one(i, "Calc.Calc")).stream().map(s -> s.caller().qualified()).toList();
    assertEquals(
        List.of(), callers, "a field initializer is not a method body, so it is not found");
  }

  @Test
  void aSourceWithNoOutlineLeavesTheFileAsTextOnly() {
    Map<String, String> texts = Map.of("A.java", "class A { void f() {} }\n");
    SymbolIndex i = SymbolIndex.build(texts, (path, text) -> Optional.empty());
    assertEquals(List.of(), i.symbols());
    assertEquals("class A { void f() {} }\n", i.text("A.java"));
  }

  @Test
  void theSameInputGivesTheSameAnswers() {
    assertEquals(index().symbols(), index().symbols());
    SymbolIndex a = index();
    SymbolIndex b = index();
    assertEquals(a.callSitesOf(one(a, "Calc.discount")), b.callSitesOf(one(b, "Calc.discount")));
  }
}
