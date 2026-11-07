package money;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
  @Test
  void addsCentsInTheSameCurrency() {
    Money sum = new Money(250, "EUR").plus(new Money(175, "EUR"));
    assertEquals(425, sum.cents());
    assertEquals("EUR", sum.currency());
  }

  @Test
  void differentCurrenciesAreRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new Money(1, "EUR").plus(new Money(1, "USD")));
  }

  @Test
  void theOriginalsAreNotChanged() {
    Money a = new Money(100, "EUR");
    a.plus(new Money(5, "EUR"));
    assertEquals(100, a.cents());
  }
}
