package greet;

public class Greeter {
  private final String prefix;

  public Greeter(String prefix) {
    this.prefix = prefix;
  }

  public String greet(String name) {
    String who = name == null || name.isBlank() ? "stranger" : name.strip();
    return prefix + ", " + who + "!";
  }
}
