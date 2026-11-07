package greet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreeterTest {
  @Test
  void greetsByName() {
    assertEquals("Hello, Ada!", new Greeter("Hello").greet("  Ada "));
  }

  @Test
  void nullGetsAStranger() {
    assertEquals("Hello, stranger!", new Greeter("Hello").greet(null));
  }

  @Test
  void blankGetsAStranger() {
    assertEquals("Hi, stranger!", new Greeter("Hi").greet("   "));
  }
}
