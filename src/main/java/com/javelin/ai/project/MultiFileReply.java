package com.javelin.ai.project;

import com.javelin.ai.AiException;
import com.javelin.ai.ChangeChecker;
import com.javelin.ai.ChangeReply;
import com.javelin.ai.EditBlocks;
import com.javelin.ai.FailureReason;
import com.javelin.ai.Finding;
import com.javelin.ai.JsonEdits;
import com.javelin.ai.MarkdownText;
import com.javelin.ai.ResponseParser;
import com.javelin.ai.SensitiveFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a model's reply to a project request as coordinated changes to several files. A file is a
 * heading ({@code === FILE: path ===} for an existing file, {@code === NEW FILE: path ===} for a
 * new one) followed by edit blocks, a diff or one code block; a reply made of a git-style diff over
 * several files is understood too, and so is a reply of JSON edits ({@link JsonEdits}), whose edits
 * name their file. Each file is checked on its own: the path must stay inside the project and name
 * something the editor may change, the text is applied by content (never by line number) and the
 * result is checked like a single-file change. A part that cannot be used is reported with its file
 * in {@code problems}, and nothing is ever applied here.
 */
public final class MultiFileReply {
  private MultiFileReply() {}

  /** The most files one reply may change. */
  public static final int MAX_FILES = 40;

  /** The largest new file or result accepted, in characters. */
  static final int MAX_FILE_CHARS = 1_000_000;

  private static final Pattern HEADING =
      Pattern.compile(
          "^\\s*[#=*\\s]*(NEW\\s+FILE|FILE)\\s*:\\s*(.+?)\\s*[=*\\s]*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern DIFF_NEW = Pattern.compile("^\\+\\+\\+ (?:b/)?(\\S.*?)\\s*$");
  private static final Pattern DIFF_OLD = Pattern.compile("^--- (?:a/)?(\\S.*?)\\s*$");

  private record Section(String path, boolean declaredNew, String body) {}

  /**
   * True when the reply has at least one file heading or diff header: it is meant as a change to
   * files and not as an answer in words.
   */
  public static boolean looksLike(String reply) {
    String text = ChangeReply.withoutReasoning(reply);
    return JsonEdits.looksLike(text) || !headings(text).isEmpty() || !diffSections(text).isEmpty();
  }

  public static ProjectProposal parse(
      String reply, ProjectReader reader, ProjectSnapshot snapshot, ScanOptions options) {
    return parse(reply, reader, snapshot, options, Map.of());
  }

  /**
   * As above, for a reply that continues earlier work that is not on disk: {@code overlay} maps a
   * path to the text the file has at this point (a file an earlier step of a plan changed or
   * created). Such a file is changed from that text, not from the file on disk, and a file that
   * exists only there counts as existing.
   */
  public static ProjectProposal parse(
      String reply,
      ProjectReader reader,
      ProjectSnapshot snapshot,
      ScanOptions options,
      Map<String, String> overlay) {
    String text = ChangeReply.withoutReasoning(reply);
    List<Section> sections;
    String explanation = "";
    List<FileChange> changes = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    List<FailureReason> failures = new ArrayList<>();
    if (JsonEdits.looksLike(text)) {
      try {
        JsonEdits.Parsed json = JsonEdits.parse(text);
        explanation = json.explanation();
        sections = new ArrayList<>();
        for (String file : JsonEdits.files(json))
          sections.add(new Section(file, false, JsonEdits.section(json, file)));
      } catch (AiException ex) {
        problems.add(ex.getMessage());
        failures.add(ex.reason() == null ? FailureReason.UNPARSEABLE : ex.reason());
        return new ProjectProposal(snapshot.id(), "", changes, problems, failures);
      }
    } else {
      sections = headings(text);
      if (!sections.isEmpty()) explanation = textBeforeFirstHeading(text);
      else {
        sections = diffSections(text);
        if (!sections.isEmpty()) explanation = textBeforeFirstDiff(text);
      }
    }
    sections = mergeRepeatedFiles(sections);
    Set<String> seen = new HashSet<>();
    for (Section section : sections) {
      String path = normalizePath(section.path());
      if (!seen.add(path)) {
        problems.add(path + ": appears more than once in the reply; only the first is used.");
        continue;
      }
      if (changes.size() >= MAX_FILES) {
        problems.add(
            path + ": the reply changes more than " + MAX_FILES + " files; the rest are ignored.");
        continue;
      }
      try {
        changes.add(change(path, section, reader, snapshot, options, overlay));
      } catch (AiException ex) {
        problems.add(path + ": " + ex.getMessage());
        failures.add(ex.reason() == null ? FailureReason.UNPARSEABLE : ex.reason());
      } catch (IOException ex) {
        // A path that is not in the project, or a file that is not there: the model named a
        // file that does not exist.
        problems.add(path + ": " + ex.getMessage());
        failures.add(FailureReason.WRONG_FILE);
      }
    }
    changes.removeIf(
        c -> c.original().equals(c.replacement()) && c.kind() == FileChange.Kind.MODIFY);
    return new ProjectProposal(snapshot.id(), explanation, changes, problems, failures);
  }

  private static String normalizePath(String raw) {
    String path = raw.replace('\\', '/');
    while (path.startsWith("./")) path = path.substring(2);
    return path;
  }

  /**
   * Small models often write a file heading again for each change to the same file. When every
   * part for a file is made of edit blocks, the parts are one list of edits, in the order written,
   * instead of the later ones being dropped. A repeated heading of anything else (a new file, a
   * diff, a whole copy) is still left for the caller to report, since two such parts cannot be
   * combined.
   */
  private static List<Section> mergeRepeatedFiles(List<Section> sections) {
    List<Section> out = new ArrayList<>();
    Map<String, Integer> firstAt = new HashMap<>();
    for (Section s : sections) {
      String key = normalizePath(s.path());
      Integer i = firstAt.get(key);
      if (i == null) {
        firstAt.put(key, out.size());
      } else {
        Section first = out.get(i);
        if (!first.declaredNew()
            && !s.declaredNew()
            && EditBlocks.looksLike(first.body())
            && EditBlocks.looksLike(s.body())) {
          out.set(i, new Section(first.path(), false, first.body() + "\n" + s.body()));
          continue;
        }
      }
      out.add(s);
    }
    return out;
  }

  private static FileChange change(
      String path,
      Section section,
      ProjectReader reader,
      ProjectSnapshot snapshot,
      ScanOptions options,
      Map<String, String> overlay)
      throws AiException, IOException {
    refuseIfNotChangeable(path);
    Path file = reader.resolve(path);
    String language = FileKinds.languageOf(path);
    String earlier = overlay.get(path);
    boolean exists = earlier != null || Files.isRegularFile(file);
    if (earlier == null && Files.exists(file) && !exists)
      throw new AiException(
          AiException.Kind.REFUSED, FailureReason.WRONG_FILE, "is not a regular file.");
    boolean inContext = snapshot.sentHashes().containsKey(path);
    List<Finding> findings = new ArrayList<>();
    if (!exists && ChangeReply.isJsonEdits(section.body())) {
      // JSON edits create a file with one edit that has an empty "old" and the content as "new"
      String content = newFileFromJson(section.body());
      if (content.length() > MAX_FILE_CHARS)
        throw new AiException(
            AiException.Kind.REFUSED,
            FailureReason.OVERSIZED,
            "the new file is larger than " + MAX_FILE_CHARS + " characters.");
      String code = content.endsWith("\n") ? content : content + "\n";
      findings.addAll(ChangeChecker.check(language, "", code));
      return new FileChange(
          path,
          FileChange.Kind.CREATE,
          "",
          code,
          section.body(),
          ChangeReply.Format.WHOLE_BLOCK,
          false,
          findings);
    }
    if (!exists) {
      if (!section.declaredNew())
        findings.add(
            Finding.warning(
                "The reply treats this as an existing file, but there is no such file; it is"
                    + " proposed as a new file."));
      ResponseParser.Parsed block = parseNewFile(section.body(), MarkdownText.is(language, path));
      String code = block.code().endsWith("\n") ? block.code() : block.code() + "\n";
      if (code.length() > MAX_FILE_CHARS)
        throw new AiException(
            AiException.Kind.REFUSED,
            FailureReason.OVERSIZED,
            "the new file is larger than " + MAX_FILE_CHARS + " characters.");
      findings.addAll(ChangeChecker.check(language, "", code));
      return new FileChange(
          path,
          FileChange.Kind.CREATE,
          "",
          code,
          section.body(),
          ChangeReply.Format.WHOLE_BLOCK,
          false,
          findings);
    }
    if (section.declaredNew())
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.WRONG_FILE,
          "exists already, so it is not replaced by a new file. Ask for a change to it instead.");
    if (!inContext)
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.WRONG_FILE,
          "was not shown to the model, so a change to it is not based on what the file contains"
              + " and is not proposed.");
    String original = earlier != null ? earlier : reader.readNow(path, options.maxFileBytes());
    if (!ProjectReader.hash(original).equals(snapshot.sentHashes().get(path)))
      findings.add(
          Finding.warning(
              "The file changed after the project was sent; the change was matched against its"
                  + " current text."));
    ChangeReply.Interpreted interpreted =
        ChangeReply.interpret(section.body(), original, path, language);
    if (snapshot.partialPaths().contains(path)
        && interpreted.format() == ChangeReply.Format.WHOLE_BLOCK)
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.NO_EDIT_BLOCK,
          "was shown to the model only in part, so a whole new copy of it is not accepted: it"
              + " would drop the code the model never saw. Ask for edit blocks instead.");
    String replacement =
        ResponseParser.matchLineEndings(
            original, ResponseParser.matchTrailingNewline(original, interpreted.code()));
    if (replacement.length() > MAX_FILE_CHARS)
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.OVERSIZED,
          "the result is larger than " + MAX_FILE_CHARS + " characters.");
    findings.addAll(interpreted.notes());
    findings.addAll(ChangeChecker.check(language, original, replacement));
    return new FileChange(
        path,
        FileChange.Kind.MODIFY,
        original,
        replacement,
        section.body(),
        interpreted.format(),
        inContext,
        findings,
        interpreted.anchors());
  }

  /**
   * The content of a new file from JSON edits: exactly one edit, with an empty "old", whose "new"
   * is the whole file. Anything else for a file that does not exist is not a new file.
   */
  private static String newFileFromJson(String body) throws AiException {
    JsonEdits.Parsed parsed = JsonEdits.parse(body);
    if (parsed.edits().size() != 1 || !parsed.edits().get(0).edit().search().isBlank())
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.WRONG_FILE,
          "does not exist, and the reply changes it instead of creating it (a new file is one edit"
              + " with an empty \"old\").");
    String content = parsed.edits().get(0).edit().replace();
    if (content.isBlank())
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.NO_EDIT_BLOCK,
          "would be created with no content, so it is not proposed.");
    return content;
  }

  private static ResponseParser.Parsed parseNewFile(String body, boolean markdown)
      throws AiException {
    if (UnifiedPatchHelper.isCreationDiff(body))
      return new ResponseParser.Parsed("", UnifiedPatchHelper.addedText(body), 0);
    return ResponseParser.parse(body, markdown);
  }

  /**
   * Paths the model may not change: credentials, version-control and tool folders, build output,
   * and the editor's own project settings (they can hold commands that would run later).
   */
  public static void refuseIfNotChangeable(String path) throws AiException {
    String name = path.substring(path.lastIndexOf('/') + 1);
    if (SensitiveFiles.isSensitive(name))
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.WRONG_FILE,
          "is a file for credentials, so the AI may not change it.");
    for (String part : path.split("/")) {
      String lower = part.toLowerCase(Locale.ROOT);
      if (ProjectScanner.GENERATED_DIRECTORIES.contains(lower)
          || ProjectScanner.SENSITIVE_DIRECTORIES.contains(lower))
        throw new AiException(
            AiException.Kind.REFUSED,
            FailureReason.WRONG_FILE,
            "is inside '"
                + part
                + "', which is not part of the project's own source, so the AI may not change it.");
    }
  }

  // ---- finding the files in a reply ----

  private static List<Section> headings(String text) {
    List<Section> out = new ArrayList<>();
    String[] lines = text.split("\\R", -1);
    String path = null;
    boolean declaredNew = false;
    StringBuilder body = new StringBuilder();
    for (String line : lines) {
      Matcher m = HEADING.matcher(line);
      boolean heading = m.matches() && looksLikePath(cleanPath(m.group(2)));
      if (heading) {
        if (path != null) out.add(new Section(path, declaredNew, body.toString().strip()));
        String raw = m.group(2);
        declaredNew =
            m.group(1).toUpperCase(Locale.ROOT).startsWith("NEW")
                || raw.toLowerCase(Locale.ROOT).matches(".*\\((?:new|create|created)\\)\\s*$");
        path = cleanPath(raw);
        body.setLength(0);
      } else if (path != null) {
        body.append(line).append('\n');
      }
    }
    if (path != null) out.add(new Section(path, declaredNew, body.toString().strip()));
    return out;
  }

  private static String cleanPath(String raw) {
    String p = raw.strip().replaceAll("(?i)\\s*\\((?:new|create|created)\\)\\s*$", "");
    p = p.replaceAll("^[`'\"*\\s]+|[`'\"*=\\s]+$", "");
    return p;
  }

  private static boolean looksLikePath(String p) {
    return !p.isEmpty() && !p.contains(" ") && (p.contains("/") || p.contains("."));
  }

  private static String textBeforeFirstHeading(String text) {
    StringBuilder out = new StringBuilder();
    for (String line : text.split("\\R", -1)) {
      Matcher m = HEADING.matcher(line);
      if (m.matches() && looksLikePath(cleanPath(m.group(2)))) break;
      if (!line.strip().startsWith("```")) out.append(line).append('\n');
    }
    return out.toString().strip();
  }

  /** The files of a diff that covers several (one section per {@code ---}/{@code +++} pair). */
  private static List<Section> diffSections(String text) {
    List<Section> out = new ArrayList<>();
    String[] lines = text.split("\\R", -1);
    String path = null;
    boolean isNew = false;
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.strip().startsWith("```")) continue;
      Matcher old = DIFF_OLD.matcher(line);
      if (old.matches() && i + 1 < lines.length && DIFF_NEW.matcher(lines[i + 1]).matches()) {
        if (path != null) out.add(new Section(path, isNew, body.toString().strip()));
        isNew = old.group(1).equals("/dev/null");
        Matcher next = DIFF_NEW.matcher(lines[i + 1]);
        next.matches();
        path = next.group(1);
        body.setLength(0);
        body.append(line).append('\n').append(lines[i + 1]).append('\n');
        i++;
      } else if (path != null && !line.startsWith("diff --git") && !line.startsWith("index ")) {
        body.append(line).append('\n');
      }
    }
    if (path != null) out.add(new Section(path, isNew, body.toString().strip()));
    out.removeIf(s -> !UnifiedPatchHelper.hasHunk(s.body()));
    // ChangeReply reads a diff from a fenced block, as models usually write it
    out.replaceAll(s -> new Section(s.path(), s.declaredNew(), "```diff\n" + s.body() + "\n```"));
    return out;
  }

  private static String textBeforeFirstDiff(String text) {
    StringBuilder out = new StringBuilder();
    String[] lines = text.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("--- ") && i + 1 < lines.length && lines[i + 1].startsWith("+++ "))
        break;
      if (lines[i].startsWith("diff --git")) break;
      if (!lines[i].strip().startsWith("```")) out.append(lines[i]).append('\n');
    }
    return out.toString().strip();
  }
}
