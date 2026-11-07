package receipt;

import java.util.List;

public final class Receipt {
  private Receipt() {}

  public static String render(List<Item> items) {
    StringBuilder out = new StringBuilder();
    long total = 0;
    for (Item item : items) {
      long line = item.quantity() * item.unitCents();
      out.append(String.format("%s x%d @ %d.%02d", item.name(), item.quantity(),
          item.unitCents() / 100, item.unitCents() % 100));
      out.append('\n');
      total += line;
    }
    out.append("total ").append(total / 100).append('.').append(String.format("%02d", total % 100));
    return out.toString();
  }
}
