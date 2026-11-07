package web;

public final class Slugifier {
  private Slugifier() {}

  /** Lower case, every run of other characters becomes one dash, no dash at either end. */
  public static String slugify(String title) {
    String s = title.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    s = s.replaceAll("^-+", "").replaceAll("-+$", "");
    return s;
  }
}
