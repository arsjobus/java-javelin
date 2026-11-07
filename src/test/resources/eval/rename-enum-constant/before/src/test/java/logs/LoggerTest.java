package logs;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggerTest {
  @Test
  void theConstantIsCalledWarning() {
    assertEquals("WARNING", Level.WARNING.name());
  }

  @Test
  void warnLogsAtWarning() {
    Logger l = new Logger();
    l.warn("careful");
    l.log(Level.DEBUG, "hidden");
    assertEquals(List.of("WARNING careful"), l.lines());
  }
}
