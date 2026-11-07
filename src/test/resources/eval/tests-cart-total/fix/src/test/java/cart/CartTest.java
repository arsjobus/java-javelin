package cart;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartTest {
  @Test
  void emptyCartIsFree() {
    assertEquals(0, new Cart().total());
  }

  @Test
  void sumsThePrices() {
    Cart c = new Cart();
    c.add(100);
    c.add(250);
    assertEquals(350, c.total());
  }

  @Test
  void fiveItemsGetFivePercentOff() {
    Cart c = new Cart();
    for (int i = 0; i < 5; i++) c.add(200);
    assertEquals(950, c.total());
  }

  @Test
  void fourItemsDoNot() {
    Cart c = new Cart();
    for (int i = 0; i < 4; i++) c.add(200);
    assertEquals(800, c.total());
  }

  @Test
  void negativePricesAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new Cart().add(-1));
  }
}
