package lookup;

public class Report {
  private final Finder finder;

  public Report(Finder finder) {
    this.finder = finder;
  }

  public String line(String id) {
    return finder.find(id).map(name -> id + ": " + name).orElse(id + ": unknown");
  }

  public boolean known(String id) {
    return finder.find(id).isPresent();
  }
}
