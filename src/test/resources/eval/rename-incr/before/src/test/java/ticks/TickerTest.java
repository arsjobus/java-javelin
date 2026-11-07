package ticks;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickerTest {
  @Test
  void counterIncrements() {
    Counter c = new Counter();
    c.increment();
    c.increment();
    assertEquals(2, c.value());
  }

  @Test
  void tickerCountsTicks() {
    Ticker t = new Ticker();
    t.tick(3);
    assertEquals(3, t.ticks());
  }
}
