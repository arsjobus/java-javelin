package stack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntStackTest {
  @Test
  void peekReturnsTheTopAndKeepsIt() {
    IntStack s = new IntStack();
    s.push(4);
    s.push(9);
    assertEquals(9, s.peek());
    assertEquals(2, s.size());
    assertEquals(9, s.pop());
  }

  @Test
  void peekOnAnEmptyStackThrows() {
    assertThrows(IllegalStateException.class, () -> new IntStack().peek());
  }
}
