package lookup;

import java.util.Map;
import java.util.Optional;

public class Finder {
  private final Map<String, String> names;

  public Finder(Map<String, String> names) {
    this.names = names;
  }

  /** The name for the id, if there is one. */
  public Optional<String> find(String id) {
    return Optional.ofNullable(names.get(id));
  }
}
