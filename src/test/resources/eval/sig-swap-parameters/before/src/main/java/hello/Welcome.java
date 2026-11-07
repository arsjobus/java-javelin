package hello;

public class Welcome {
  private final Greeter greeter = new Greeter();

  public String forUser(String user) {
    return greeter.greet(user, "Welcome");
  }

  public String forAdmin(String admin) {
    return greeter.greet(admin, "Good day");
  }
}
