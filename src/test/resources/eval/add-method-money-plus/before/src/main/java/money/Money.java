package money;

public final class Money {
  private final long cents;
  private final String currency;

  public Money(long cents, String currency) {
    this.cents = cents;
    this.currency = currency;
  }

  public long cents() {
    return cents;
  }

  public String currency() {
    return currency;
  }

  public Money negate() {
    return new Money(-cents, currency);
  }
}
