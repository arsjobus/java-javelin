package shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PricingTest {
  @Test
  void exactlyTheThresholdIsDiscounted() {
    assertEquals(9000, Pricing.discounted(10_000));
  }

  @Test
  void aboveTheThresholdIsDiscounted() {
    assertEquals(18_000, Pricing.discounted(20_000));
  }

  @Test
  void belowIsNot() {
    assertEquals(9_999, Pricing.discounted(9_999));
  }
}
