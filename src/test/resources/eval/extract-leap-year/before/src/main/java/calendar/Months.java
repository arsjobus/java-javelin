package calendar;

public final class Months {
  private Months() {}

  public static int daysInMonth(int year, int month) {
    switch (month) {
      case 2:
        boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
        return leap ? 29 : 28;
      case 4:
      case 6:
      case 9:
      case 11:
        return 30;
      default:
        return 31;
    }
  }
}
