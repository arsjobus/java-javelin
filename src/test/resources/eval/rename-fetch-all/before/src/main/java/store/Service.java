package store;

import java.util.List;

public class Service {
  private final Repository repository;

  public Service(Repository repository) {
    this.repository = repository;
  }

  public int count() {
    return repository.fetchAll().size();
  }

  public List<String> sorted() {
    return repository.fetchAll().stream().sorted().toList();
  }
}
