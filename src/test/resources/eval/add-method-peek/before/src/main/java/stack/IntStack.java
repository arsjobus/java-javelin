package stack;

public class IntStack {
  private final int[] items = new int[16];
  private int size;

  public void push(int value) {
    if (size == items.length) throw new IllegalStateException("full");
    items[size++] = value;
  }

  public int pop() {
    if (size == 0) throw new IllegalStateException("empty");
    return items[--size];
  }

  public int size() {
    return size;
  }
}
