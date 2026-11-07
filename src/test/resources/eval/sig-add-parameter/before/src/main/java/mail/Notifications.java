package mail;

public class Notifications {
  private final Mailer mailer;

  public Notifications(Mailer mailer) {
    this.mailer = mailer;
  }

  public void welcome(String address) {
    mailer.send(address, "Welcome aboard");
  }

  public void goodbye(String address) {
    mailer.send(address, "Sorry to see you go");
  }
}
