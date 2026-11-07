package store;

import java.util.List;

public class Service {
  private final Repository repository;

  public Service(Repository repository) {
    this.repository = repository;
  }

  public int count() {
    return repository.findAll().size();
  }

  public List<String> sorted() {
    return repository.findAll().stream().sorted().toList();
  }
}
