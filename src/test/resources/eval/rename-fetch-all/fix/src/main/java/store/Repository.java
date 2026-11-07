package store;

import java.util.ArrayList;
import java.util.List;

public class Repository {
  private final List<String> rows = new ArrayList<>();

  public void add(String row) {
    rows.add(row);
  }

  public List<String> findAll() {
    return List.copyOf(rows);
  }
}
