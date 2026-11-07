package games;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FizzBuzzTest {
  @Test
  void plainNumbers() {
    assertEquals("1", FizzBuzz.of(1));
    assertEquals("7", FizzBuzz.of(7));
  }

  @Test
  void multiplesOfThree() {
    assertEquals("Fizz", FizzBuzz.of(9));
  }

  @Test
  void multiplesOfFive() {
    assertEquals("Buzz", FizzBuzz.of(10));
  }

  @Test
  void bothIsFizzBuzz() {
    assertEquals("FizzBuzz", FizzBuzz.of(15));
    assertEquals("FizzBuzz", FizzBuzz.of(30));
  }
}
