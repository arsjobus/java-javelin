package calendar;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonthsTest {
  @Test
  void leapYearsHaveTheirOwnTest() {
    assertTrue(Months.isLeapYear(2024));
    assertTrue(Months.isLeapYear(2000));
    assertFalse(Months.isLeapYear(1900));
    assertFalse(Months.isLeapYear(2023));
  }

  @Test
  void februaryFollowsIt() {
    assertEquals(29, Months.daysInMonth(2024, 2));
    assertEquals(28, Months.daysInMonth(2023, 2));
    assertEquals(30, Months.daysInMonth(2023, 4));
    assertEquals(31, Months.daysInMonth(2023, 1));
  }
}
