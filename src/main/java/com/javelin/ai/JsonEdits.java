package com.javelin.ai;

import com.javelin.language.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits written as JSON, for providers that can be made to answer in a given shape (Ollama's {@code
 * format}). Each edit has four fields, {@code file}, {@code anchor}, {@code old} and {@code new},
 * and means exactly what an edit block means: the lines {@code old} become the lines {@code new},
 * found by content, in the one place the lines (and the anchor) allow. JSON removes the failure
 * that small models have most with edit blocks, a marker line that is missing, misspelled or merged
 * into the code; it does not make the lines any more correct, so everything else is checked as
 * before. The text format stays understood either way ({@link ChangeReply}).
 */
public final class JsonEdits {
  private JsonEdits() {}

  /** An edit and the file it is for, as named in the reply. */
  public record FileEdit(String file, EditBlocks.Edit edit) {}

  /** {@code explanation} is the model's own words about the change. */
  public record Parsed(List<FileEdit> edits, String explanation) {}

  /** The shape asked of the model, as JSON Schema. */
  public static final String SCHEMA =
      """
      {"type":"object","properties":{"explanation":{"type":"string"},\
      "edits":{"type":"array","items":{"type":"object","properties":{\
      "file":{"type":"string"},"anchor":{"type":"string"},"old":{"type":"string"},"new":{"type":"string"}},\
      "required":["file","anchor","old","new"]}}},"required":["explanation","edits"]}\
      """;

  /**
   * True when the reply is a JSON object with an {@code edits} list that is not empty: the shape of
   * an answer that wants to change code. A reply that is JSON with no edits is an answer in words.
   */
  public static boolean looksLike(String reply) {
    String json = jsonPart(reply);
    return json != null && json.contains("\"edits\"") && !isEmptyEdits(json);
  }

  /**
   * Reads the edits. A reply that is cut off, is not JSON, or has edits that are not objects with
   * the right fields produces no edits at all (a half-read change is never applied).
   */
  public static Parsed parse(String reply) throws AiException {
    String json = jsonPart(reply);
    if (json == null)
      throw refused(
          FailureReason.NO_EDIT_BLOCK,
          "The reply holds no JSON edits, so there is no change to propose.");
    Object root;
    try {
      root = Json.parse(json);
    } catch (IllegalArgumentException ex) {
      String t = reply.strip();
      boolean cutOff = !t.endsWith("}") && !t.endsWith("```");
      throw refused(
          cutOff ? FailureReason.TRUNCATED : FailureReason.UNPARSEABLE,
          cutOff
              ? "The reply stops inside its JSON (it was probably cut off), so nothing is proposed."
                  + " Its text is in the AI panel."
              : "The reply looks like JSON edits but cannot be read as JSON ("
                  + ex.getMessage()
                  + "), so nothing is proposed. Its text is in the AI panel.");
    }
    Map<String, Object> object = Json.asObject(root);
    List<Object> array = object == null ? null : Json.asArray(object.get("edits"));
    if (array == null)
      throw refused(
          FailureReason.UNPARSEABLE,
          "The JSON has no \"edits\" list, so there is no change to propose. Its text is in the AI"
              + " panel.");
    if (array.isEmpty())
      throw refused(
          FailureReason.NO_EDIT_BLOCK,
          "The reply has no edits, so there is no change to propose. Its text is in the AI panel.");
    List<FileEdit> edits = new ArrayList<>();
    int number = 0;
    for (Object item : array) {
      number++;
      Map<String, Object> e = Json.asObject(item);
      if (e == null)
        throw refused(
            FailureReason.UNPARSEABLE,
            "Edit " + number + " is not an object, so nothing is proposed.");
      String file = field(e, "file", number, false);
      String old = field(e, "old", number, true);
      String replacement = field(e, "new", number, true);
      Object anchor = e.get("anchor");
      if (anchor != null && !(anchor instanceof String))
        throw refused(
            FailureReason.UNPARSEABLE,
            "Edit " + number + " has an anchor that is not text, so nothing is proposed.");
      edits.add(new FileEdit(file.strip(), new EditBlocks.Edit(old, replacement, (String) anchor)));
    }
    Object explanation = object.get("explanation");
    return new Parsed(edits, explanation instanceof String s ? s.strip() : "");
  }

  /** The edits of one file, in order. */
  public static List<EditBlocks.Edit> editsFor(Parsed parsed, String file) {
    List<EditBlocks.Edit> out = new ArrayList<>();
    for (FileEdit e : parsed.edits()) if (e.file().equals(file)) out.add(e.edit());
    return out;
  }

  /** The files named, in the order they first appear. */
  public static List<String> files(Parsed parsed) {
    List<String> out = new ArrayList<>();
    for (FileEdit e : parsed.edits()) if (!out.contains(e.file())) out.add(e.file());
    return out;
  }

  /**
   * The JSON for one file's edits alone, in the same shape, so a piece of a reply can be kept and
   * read again later by {@link ChangeReply}.
   */
  public static String section(Parsed parsed, String file) {
    List<Object> edits = new ArrayList<>();
    for (FileEdit e : parsed.edits())
      if (e.file().equals(file)) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", e.file());
        m.put("anchor", e.edit().anchor() == null ? "" : e.edit().anchor());
        m.put("old", e.edit().search());
        m.put("new", e.edit().replace());
        edits.add(m);
      }
    return Json.write(Json.obj("explanation", "", "edits", edits));
  }

  /**
   * Whether the file is the one that was sent. A reply names a file the way the prompt showed it; a
   * folder in front of the name is not a difference.
   */
  static boolean sameFile(String named, String sent) {
    String a = named.replace('\\', '/');
    String b = sent.replace('\\', '/');
    return a.equals(b)
        || a.substring(a.lastIndexOf('/') + 1).equals(b.substring(b.lastIndexOf('/') + 1));
  }

  private static String field(Map<String, Object> e, String name, int number, boolean mayBeEmpty)
      throws AiException {
    Object v = e.get(name);
    if (!(v instanceof String s))
      throw refused(
          FailureReason.UNPARSEABLE,
          "Edit " + number + " has no \"" + name + "\" text, so nothing is proposed.");
    if (!mayBeEmpty && s.isBlank())
      throw refused(
          FailureReason.UNPARSEABLE,
          "Edit " + number + " has an empty \"" + name + "\", so nothing is proposed.");
    return s;
  }

  private static AiException refused(FailureReason reason, String message) {
    return new AiException(AiException.Kind.BAD_RESPONSE, reason, message);
  }

  /**
   * The JSON object in the reply: the reply without a code fence around it, from its first {@code
   * {} to its last {@code }}. Null when there is none.
   */
  private static String jsonPart(String reply) {
    String t = ChangeReply.withoutReasoning(reply);
    int open = t.indexOf('{');
    if (open < 0) return null;
    int close = t.lastIndexOf('}');
    String before = t.substring(0, open).strip();
    boolean fenceOrNothing = before.isEmpty() || before.matches("(?i)(```|~~~)\\s*(json)?");
    if (!fenceOrNothing) return null;
    return close > open ? t.substring(open, close + 1) : t.substring(open);
  }

  private static boolean isEmptyEdits(String json) {
    try {
      Map<String, Object> object = Json.asObject(Json.parse(json));
      List<Object> array = object == null ? null : Json.asArray(object.get("edits"));
      return array != null && array.isEmpty();
    } catch (IllegalArgumentException ex) {
      return false; // cut off or malformed: it still has the shape of an edit
    }
  }
}
