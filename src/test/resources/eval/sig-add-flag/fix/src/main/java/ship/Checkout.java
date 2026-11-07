package ship;

public class Checkout {
  public long totalWithShipping(long goodsCents, int grams) {
    return goodsCents + Shipping.cost(grams, false);
  }

  public long shippingOnly(int grams) {
    return Shipping.cost(grams, false);
  }
}
