package lookup;

public class Report {
  private final Finder finder;

  public Report(Finder finder) {
    this.finder = finder;
  }

  public String line(String id) {
    String name = finder.find(id);
    if (name == null) return id + ": unknown";
    return id + ": " + name;
  }

  public boolean known(String id) {
    return finder.find(id) != null;
  }
}
