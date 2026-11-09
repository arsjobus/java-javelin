package com.javelin.ai.plan;

/**
 * One unit of model work: one file and one method. {@code symbol} is the method the step changes;
 * with {@code isNew} it is the method the step adds (blank for a file that is created whole). A
 * step that names a file or method that does not exist is only valid when it says {@code isNew},
 * which is how the plan declares what it will create. {@code task} is what to do, in the planner's
 * words (or the user's, once edited).
 *
 * <p>The text form, which the user edits in the Send dialog, is one line: {@code file | method |
 * task}, where the method is {@code name}, {@code new name} or just {@code new}.
 */
public record PlanStep(String file, String symbol, boolean isNew, String task) {
  public PlanStep {
    file = normalize(file);
    symbol = symbol == null ? "" : symbol.strip();
    task = task == null ? "" : task.strip().replaceAll("\\s*\\R\\s*", " ");
  }

  /** A project-relative path with forward slashes and no leading {@code ./}. */
  static String normalize(String path) {
    String p = path == null ? "" : path.strip().replace('\\', '/');
    p = p.replaceAll("^[`'\"]+|[`'\"]+$", "");
    while (p.startsWith("./")) p = p.substring(2);
    return p;
  }

  /** The method column of the text form. */
  String methodColumn() {
    if (!isNew) return symbol;
    return symbol.isEmpty() ? "new" : "new " + symbol;
  }

  /** The step as the one editable line, without a number. */
  public String line() {
    return file + " | " + methodColumn() + " | " + task;
  }

  /** What the step is about, for messages: {@code Calc.java#add} or {@code Calc.java (new)}. */
  public String label() {
    String name = file.substring(file.lastIndexOf('/') + 1);
    if (symbol.isEmpty()) return name + (isNew ? " (new)" : "");
    return name + "#" + symbol + (isNew ? " (new)" : "");
  }
}
