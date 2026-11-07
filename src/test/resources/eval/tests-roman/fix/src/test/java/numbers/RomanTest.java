package numbers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RomanTest {
  @Test
  void simpleNumbers() {
    assertEquals("III", Roman.toRoman(3));
    assertEquals("XXVII", Roman.toRoman(27));
  }

  @Test
  void subtractiveForms() {
    assertEquals("IV", Roman.toRoman(4));
    assertEquals("IX", Roman.toRoman(9));
    assertEquals("CM", Roman.toRoman(900));
  }

  @Test
  void aLongOne() {
    assertEquals("MCMXCIV", Roman.toRoman(1994));
  }

  @Test
  void outOfRangeIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> Roman.toRoman(0));
    assertThrows(IllegalArgumentException.class, () -> Roman.toRoman(4000));
  }
}
