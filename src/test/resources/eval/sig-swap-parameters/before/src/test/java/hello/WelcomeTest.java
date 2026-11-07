package hello;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WelcomeTest {
  @Test
  void greetingComesFirst() {
    assertEquals("Hey, Sam!", new Greeter().greet("Hey", "Sam"));
  }

  @Test
  void callersStillGreetTheRightPerson() {
    Welcome w = new Welcome();
    assertEquals("Welcome, kim!", w.forUser("kim"));
    assertEquals("Good day, root!", w.forAdmin("root"));
  }
}
