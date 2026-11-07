package receipt;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptTest {
  @Test
  void aLineIsFormattedByItsOwnMethod() {
    assertEquals("tea x2 @ 1.50", Receipt.formatLine(new Item("tea", 2, 150)));
  }

  @Test
  void renderStillListsLinesAndTotal() {
    String text = Receipt.render(List.of(new Item("tea", 2, 150), new Item("cake", 1, 395)));
    assertEquals("tea x2 @ 1.50\ncake x1 @ 3.95\ntotal 6.95", text);
  }
}
