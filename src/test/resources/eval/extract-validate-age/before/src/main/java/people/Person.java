package people;

public final class Person {
  private final String name;
  private final int age;

  public Person(String name, int age) {
    if (age < 0 || age > 150) {
      throw new IllegalArgumentException("age out of range: " + age);
    }
    this.name = name;
    this.age = age;
  }

  public String name() {
    return name;
  }

  public int age() {
    return age;
  }
}
