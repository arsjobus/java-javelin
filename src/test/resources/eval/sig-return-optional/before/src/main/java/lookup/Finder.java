package lookup;

import java.util.Map;

public class Finder {
  private final Map<String, String> names;

  public Finder(Map<String, String> names) {
    this.names = names;
  }

  /** The name for the id, or null when there is none. */
  public String find(String id) {
    return names.get(id);
  }
}
