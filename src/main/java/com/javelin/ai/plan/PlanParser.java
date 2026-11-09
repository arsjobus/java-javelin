package com.javelin.ai.plan;

import com.javelin.ai.AiException;
import com.javelin.ai.ChangeReply;
import com.javelin.ai.FailureReason;
import com.javelin.language.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a plan: from a model's reply (JSON when the provider could be held to a shape, otherwise
 * lines) and from the text the user edits. Reading only decides what the steps say; whether the
 * files and methods exist is {@link PlanValidator}'s question. A reply that is cut off or only half
 * readable gives no plan at all, never a shorter one.
 */
public final class PlanParser {
  private PlanParser() {}

  /** The shape asked of the model when the provider can hold an answer to one. */
  public static final String SCHEMA =
      """
      {"type":"object","properties":{"steps":{"type":"array","items":{"type":"object",\
      "properties":{"file":{"type":"string"},"method":{"type":"string"},\
      "new":{"type":"boolean"},"task":{"type":"string"}},\
      "required":["file","method","new","task"]}}},"required":["steps"]}\
      """;

  /** Most steps read from one reply; a plan beyond the limit is refused by the validator. */
  static final int MAX_READ = 50;

  private static final Pattern LINE =
      Pattern.compile(
          "^\\s*(?:(?:\\d+\\s*[.):]|[-*\u2022])\\s*)?([^|]+?)\\s*\\|\\s*([^|]*?)\\s*\\|\\s*(.*)$");
  private static final Pattern NEW_METHOD = Pattern.compile("(?i)^new(?:\\s+(.*))?$");
  private static final Pattern TRAILING_NEW =
      Pattern.compile("(?i)^(.*?)\\s*\\((?:new|create)\\)$");

  /**
   * @param truncated the provider says the reply was cut off
   * @throws AiException BAD_RESPONSE with TRUNCATED, UNPARSEABLE or NO_EDIT_BLOCK
   */
  public static TaskPlan parseReply(String instruction, String reply, boolean truncated)
      throws AiException {
    if (truncated)
      throw bad(
          FailureReason.TRUNCATED,
          "The plan was cut off by the model, so there is no plan. Its text is in the AI panel.");
    String text = ChangeReply.withoutReasoning(reply == null ? "" : reply);
    String json = jsonPart(text);
    if (json != null) return fromJson(instruction, json);
    List<PlanStep> steps = new ArrayList<>();
    for (String line : text.split("\\R")) {
      Matcher m = LINE.matcher(line);
      if (m.matches() && steps.size() < MAX_READ) steps.add(step(m));
    }
    if (steps.isEmpty())
      throw bad(
          FailureReason.NO_EDIT_BLOCK,
          "The reply holds no plan: no line of the form  file | method | task.");
    return new TaskPlan(instruction, steps);
  }

  /**
   * Reads the lines the user edited. Unlike a reply, every line that is not blank must be a step: a
   * line with no two {@code |} is an error that names the line, not text to skip.
   */
  public static TaskPlan parseText(String instruction, String text) throws AiException {
    List<PlanStep> steps = new ArrayList<>();
    String[] lines = text.split("\\R");
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].isBlank() || lines[i].strip().startsWith("#")) continue;
      Matcher m = LINE.matcher(lines[i]);
      if (!m.matches())
        throw bad(
            FailureReason.UNPARSEABLE,
            "Line " + (i + 1) + " is not a step. Write each step as  file | method | task.");
      steps.add(step(m));
    }
    return new TaskPlan(instruction, steps);
  }

  private static PlanStep step(Matcher m) {
    String method = m.group(2).strip().replaceAll("^[`'\"]+|[`'\"]+$", "");
    boolean isNew = false;
    Matcher fresh = NEW_METHOD.matcher(method);
    if (fresh.matches()) {
      isNew = true;
      method = fresh.group(1) == null ? "" : fresh.group(1).strip();
    } else {
      Matcher trailing = TRAILING_NEW.matcher(method);
      if (trailing.matches()) {
        isNew = true;
        method = trailing.group(1).strip();
      }
    }
    return new PlanStep(m.group(1), methodName(method), isNew, m.group(3));
  }

  /** {@code Calc.add}, {@code Calc#add} and {@code add(int, int)} all mean {@code add}. */
  static String methodName(String written) {
    String name = written.strip();
    int paren = name.indexOf('(');
    if (paren >= 0) name = name.substring(0, paren).strip();
    int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('#'));
    return cut >= 0 ? name.substring(cut + 1) : name;
  }

  private static TaskPlan fromJson(String instruction, String json) throws AiException {
    Object root;
    try {
      root = Json.parse(json);
    } catch (IllegalArgumentException ex) {
      throw bad(
          FailureReason.UNPARSEABLE,
          "The plan looks like JSON but cannot be read as JSON (" + ex.getMessage() + ").");
    }
    Map<String, Object> object = Json.asObject(root);
    List<Object> array = object == null ? null : Json.asArray(object.get("steps"));
    if (array == null) throw bad(FailureReason.UNPARSEABLE, "The plan has no \"steps\" list.");
    List<PlanStep> steps = new ArrayList<>();
    for (Object item : array) {
      if (Json.asObject(item) == null)
        throw bad(FailureReason.UNPARSEABLE, "A step of the plan is not an object.");
      if (steps.size() >= MAX_READ) break;
      String method = Json.string(item, "method");
      boolean isNew = Json.bool(item, "new") || Json.bool(item, "isNew");
      Matcher fresh = NEW_METHOD.matcher(method == null ? "" : method.strip());
      if (fresh.matches()) {
        isNew = true;
        method = fresh.group(1);
      }
      steps.add(
          new PlanStep(
              Json.string(item, "file"),
              methodName(method == null ? "" : method),
              isNew,
              Json.string(item, "task")));
    }
    if (steps.isEmpty()) throw bad(FailureReason.NO_EDIT_BLOCK, "The plan has no steps.");
    return new TaskPlan(instruction, steps);
  }

  /** The JSON object of a reply that is JSON (possibly in a fence), else null. */
  private static String jsonPart(String text) {
    int open = text.indexOf('{');
    if (open < 0) return null;
    String before = text.substring(0, open).strip();
    if (!(before.isEmpty() || before.matches("(?i)(```|~~~)\\s*(json)?"))) return null;
    int close = text.lastIndexOf('}');
    return close > open ? text.substring(open, close + 1) : text.substring(open);
  }

  private static AiException bad(FailureReason reason, String message) {
    return new AiException(AiException.Kind.BAD_RESPONSE, reason, message);
  }
}
