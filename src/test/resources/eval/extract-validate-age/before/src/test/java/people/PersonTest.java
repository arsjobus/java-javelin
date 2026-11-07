package people;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonTest {
  @Test
  void validateAgeIsItsOwnMethod() {
    Person.validateAge(30);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Person.validateAge(-1));
    assertEquals("age out of range: -1", e.getMessage());
  }

  @Test
  void theConstructorStillChecks() {
    assertThrows(IllegalArgumentException.class, () -> new Person("Ada", 151));
    assertEquals(36, new Person("Ada", 36).age());
  }
}
