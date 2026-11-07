package mail;

import java.util.ArrayList;
import java.util.List;

public class Mailer {
  private final List<String> sent = new ArrayList<>();

  public void send(String to, String subject, String body) {
    sent.add(to + "|" + subject + "|" + body);
  }

  public List<String> sent() {
    return sent;
  }
}
