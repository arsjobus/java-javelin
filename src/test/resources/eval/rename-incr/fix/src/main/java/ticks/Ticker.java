package ticks;

public class Ticker {
  private final Counter counter = new Counter();

  public void tick(int times) {
    for (int i = 0; i < times; i++) {
      counter.increment();
    }
  }

  public int ticks() {
    return counter.value();
  }
}
