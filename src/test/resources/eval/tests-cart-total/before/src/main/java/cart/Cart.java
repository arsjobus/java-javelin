package cart;

import java.util.ArrayList;
import java.util.List;

public class Cart {
  private final List<Long> pricesCents = new ArrayList<>();

  public void add(long priceCents) {
    if (priceCents < 0) throw new IllegalArgumentException("negative price");
    pricesCents.add(priceCents);
  }

  /** The sum of the prices; carts of five or more items get 5% off the sum. */
  public long total() {
    long sum = 0;
    for (long p : pricesCents) sum += p;
    if (pricesCents.size() >= 5) sum -= sum / 20;
    return sum;
  }
}
