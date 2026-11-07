package web;

public final class Slugifier {
  private Slugifier() {}

  public static String slugify(String title) {
    String s = title.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    return s;
  }
}
