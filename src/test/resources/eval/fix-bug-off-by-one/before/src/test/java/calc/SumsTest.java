package calc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SumsTest {
  @Test
  void sumIncludesN() {
    assertEquals(15, Sums.sumUpTo(5));
    assertEquals(1, Sums.sumUpTo(1));
  }

  @Test
  void zeroGivesZero() {
    assertEquals(0, Sums.sumUpTo(0));
  }

  @Test
  void sumOfStillWorks() {
    assertEquals(6, Sums.sumOf(1, 2, 3));
  }
}
