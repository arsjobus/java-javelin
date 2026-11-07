package logs;

import java.util.ArrayList;
import java.util.List;

public class Logger {
  private final List<String> lines = new ArrayList<>();
  private Level threshold = Level.INFO;

  public void threshold(Level level) {
    this.threshold = level;
  }

  public void log(Level level, String message) {
    if (level.ordinal() >= threshold.ordinal()) lines.add(level + " " + message);
  }

  public void warn(String message) {
    log(Level.WARN, message);
  }

  public List<String> lines() {
    return lines;
  }
}
