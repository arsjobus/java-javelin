package store;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTest {
  @Test
  void theRepositoryHasFindAll() {
    Repository r = new Repository();
    r.add("b");
    r.add("a");
    assertEquals(List.of("b", "a"), r.findAll());
  }

  @Test
  void theServiceStillWorks() {
    Repository r = new Repository();
    r.add("b");
    r.add("a");
    Service s = new Service(r);
    assertEquals(2, s.count());
    assertEquals(List.of("a", "b"), s.sorted());
  }
}
