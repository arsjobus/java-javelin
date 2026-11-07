package mail;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationsTest {
  @Test
  void sendTakesASubject() {
    Mailer m = new Mailer();
    m.send("a@x.org", "Hi", "text");
    assertEquals(List.of("a@x.org|Hi|text"), m.sent());
  }

  @Test
  void callersGiveSubjects() {
    Mailer m = new Mailer();
    Notifications n = new Notifications(m);
    n.welcome("a@x.org");
    n.goodbye("b@x.org");
    assertEquals(2, m.sent().size());
    assertTrue(m.sent().get(0).startsWith("a@x.org|"));
    assertTrue(m.sent().get(0).endsWith("|Welcome aboard"));
    assertTrue(m.sent().get(1).endsWith("|Sorry to see you go"));
    assertEquals(3, m.sent().get(0).split("\\|").length);
  }
}
