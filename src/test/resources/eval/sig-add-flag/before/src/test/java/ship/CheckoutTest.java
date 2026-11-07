package ship;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutTest {
  @Test
  void expressCostsDouble() {
    assertEquals(400, Shipping.cost(1000, false));
    assertEquals(800, Shipping.cost(1000, true));
  }

  @Test
  void checkoutKeepsTheNormalPrice() {
    Checkout c = new Checkout();
    assertEquals(400, c.shippingOnly(1000));
    assertEquals(5400, c.totalWithShipping(5000, 1000));
  }
}
