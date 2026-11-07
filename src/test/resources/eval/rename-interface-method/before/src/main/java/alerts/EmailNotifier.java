package alerts;

import java.util.ArrayList;
import java.util.List;

public class EmailNotifier implements Notifier {
  private final List<String> outbox = new ArrayList<>();

  @Override
  public void send(String message) {
    outbox.add("email: " + message);
  }

  public List<String> outbox() {
    return outbox;
  }
}
