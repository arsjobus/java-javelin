package stats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AverageTest {
  @Test
  void keepsTheFraction() {
    assertEquals(1.5, Average.average(new int[] {1, 2}), 1e-9);
  }

  @Test
  void emptyIsZero() {
    assertEquals(0.0, Average.average(new int[0]), 1e-9);
  }

  @Test
  void wholeAveragesStillWork() {
    assertEquals(4.0, Average.average(new int[] {2, 4, 6}), 1e-9);
  }
}
