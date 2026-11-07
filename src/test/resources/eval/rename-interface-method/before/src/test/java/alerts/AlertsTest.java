package alerts;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlertsTest {
  @Test
  void theInterfaceDelivers() {
    EmailNotifier n = new EmailNotifier();
    Notifier asInterface = n;
    asInterface.deliver("hi");
    assertEquals(List.of("email: hi"), n.outbox());
  }

  @Test
  void alertsGoThroughDeliver() {
    EmailNotifier n = new EmailNotifier();
    new Alerts(n).lowDisk(91);
    assertEquals(List.of("email: disk at 91%"), n.outbox());
  }
}
