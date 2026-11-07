package hello;

public class Welcome {
  private final Greeter greeter = new Greeter();

  public String forUser(String user) {
    return greeter.greet("Welcome", user);
  }

  public String forAdmin(String admin) {
    return greeter.greet("Good day", admin);
  }
}
