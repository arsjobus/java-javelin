package alerts;

public class Alerts {
  private final Notifier notifier;

  public Alerts(Notifier notifier) {
    this.notifier = notifier;
  }

  public void lowDisk(int percent) {
    notifier.send("disk at " + percent + "%");
  }
}
