package util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntsTest {
  @Test
  void valuesInsideTheRangeStay() {
    assertEquals(5, Ints.clamp(5, 0, 10));
  }

  @Test
  void valuesOutsideAreMovedToTheNearestEnd() {
    assertEquals(0, Ints.clamp(-3, 0, 10));
    assertEquals(10, Ints.clamp(99, 0, 10));
  }

  @Test
  void anEmptyRangeIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> Ints.clamp(1, 5, 2));
  }
}
