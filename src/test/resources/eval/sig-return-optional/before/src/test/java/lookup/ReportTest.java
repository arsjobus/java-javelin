package lookup;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportTest {
  private final Finder finder = new Finder(Map.of("1", "Ada"));

  @Test
  void findReturnsAnOptional() {
    Optional<String> found = finder.find("1");
    assertEquals("Ada", found.orElseThrow());
    assertTrue(finder.find("2").isEmpty());
  }

  @Test
  void reportStillWorks() {
    Report r = new Report(finder);
    assertEquals("1: Ada", r.line("1"));
    assertEquals("2: unknown", r.line("2"));
    assertTrue(r.known("1"));
    assertFalse(r.known("2"));
  }
}
