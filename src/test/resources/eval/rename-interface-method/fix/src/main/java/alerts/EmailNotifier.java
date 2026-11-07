package alerts;

import java.util.ArrayList;
import java.util.List;

public class EmailNotifier implements Notifier {
  private final List<String> outbox = new ArrayList<>();

  @Override
  public void deliver(String message) {
    outbox.add("email: " + message);
  }

  public List<String> outbox() {
    return outbox;
  }
}
