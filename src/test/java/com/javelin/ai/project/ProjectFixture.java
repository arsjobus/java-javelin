package com.javelin.ai.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A small Java project on disk for the scanner, context and selection tests. */
final class ProjectFixture {
  private ProjectFixture() {}

  static Path write(Path root, String relative, String text) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  /** A project with an editor, a Java parser, a diagram renderer, an AI client and their tests. */
  static Path miniEditor(Path root) throws IOException {
    write(
        root,
        "README.md",
        "# Mini editor\n\nA small code editor. It highlights code and shows diagrams.\n");
    write(
        root,
        "ARCHITECTURE.md",
        "# Architecture\n\n"
            + "The editor holds a Document. The parse package reads Java source into a ClassModel."
            + " The ui package shows windows.\n");
    write(root, "AGENTS.md", "# Rules\n\nKeep changes small. Add tests.\n");
    write(
        root,
        "pom.xml",
        "<project>\n"
            + "  <dependencies>\n"
            + "    <dependency>\n"
            + "      <groupId>org.junit.jupiter</groupId>\n"
            + "      <artifactId>junit-jupiter</artifactId>\n"
            + "      <version>5.11.4</version>\n"
            + "      <scope>test</scope>\n"
            + "    </dependency>\n"
            + "  </dependencies>\n"
            + "</project>\n");
    write(root, ".gitignore", "target/\n*.log\nsecret-notes.txt\n");
    write(root, ".env", "API_KEY=abcdef1234567890\n");
    write(root, "debug.log", "noise\n");
    write(root, "secret-notes.txt", "private\n");
    write(root, "target/classes/demo/App.class", "\u0000\u0001binary");
    write(
        root,
        "src/main/java/demo/App.java",
        "package demo;\n\n"
            + "import demo.ui.MainWindow;\n\n"
            + "public class App {\n"
            + "  public static void main(String[] args) {\n"
            + "    new MainWindow().show();\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/editor/Editor.java",
        "package demo.editor;\n\n"
            + "public class Editor {\n"
            + "  private final Document document = new Document();\n"
            + "  private final Highlighter highlighter = new Highlighter();\n\n"
            + "  public String text() {\n"
            + "    return document.text();\n"
            + "  }\n\n"
            + "  public String highlight() {\n"
            + "    return highlighter.apply(text());\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/editor/Document.java",
        "package demo.editor;\n\n"
            + "public class Document {\n"
            + "  private String text = \"\";\n\n"
            + "  public String text() {\n"
            + "    return text;\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/editor/Highlighter.java",
        "package demo.editor;\n\n"
            + "public class Highlighter {\n"
            + "  public String apply(String text) {\n"
            + "    return text;\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/parse/ClassModel.java",
        "package demo.parse;\n\n"
            + "import java.util.List;\n\n"
            + "public record ClassModel(String name, List<String> methods, List<String> fields)"
            + " {}\n");
    write(
        root,
        "src/main/java/demo/parse/JavaParser.java",
        "package demo.parse;\n\n"
            + "public class JavaParser {\n"
            + "  public ClassModel parse(String source) {\n"
            + "    return new ClassModel(\"X\", java.util.List.of(), java.util.List.of());\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/diagram/DiagramRenderer.java",
        "package demo.diagram;\n\n"
            + "import demo.parse.ClassModel;\n\n"
            + "public class DiagramRenderer {\n"
            + "  public String render(ClassModel model) {\n"
            + "    return \"[\" + model.name() + \"]\";\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/ai/AiClient.java",
        "package demo.ai;\n\n"
            + "public class AiClient {\n"
            + "  public String ask(String prompt) {\n"
            + "    return prompt;\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/ui/MainWindow.java",
        "package demo.ui;\n\n"
            + "import demo.editor.Editor;\n\n"
            + "public class MainWindow {\n"
            + "  private final Editor editor = new Editor();\n\n"
            + "  public void show() {\n"
            + "    System.out.println(editor.text());\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/main/java/demo/util/Strings.java",
        "package demo.util;\n\n"
            + "public final class Strings {\n"
            + "  private Strings() {}\n\n"
            + "  public static boolean blank(String s) {\n"
            + "    return s == null || s.isBlank();\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/test/java/demo/parse/JavaParserTest.java",
        "package demo.parse;\n\n"
            + "import org.junit.jupiter.api.Test;\n\n"
            + "class JavaParserTest {\n"
            + "  @Test\n"
            + "  void parses() {\n"
            + "    new JavaParser().parse(\"class X {}\");\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/test/java/demo/diagram/DiagramRendererTest.java",
        "package demo.diagram;\n\n"
            + "import demo.parse.ClassModel;\n"
            + "import org.junit.jupiter.api.Test;\n\n"
            + "class DiagramRendererTest {\n"
            + "  @Test\n"
            + "  void renders() {\n"
            + "    new DiagramRenderer().render(new ClassModel(\"X\", java.util.List.of(),"
            + " java.util.List.of()));\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/test/java/demo/editor/EditorTest.java",
        "package demo.editor;\n\n"
            + "import org.junit.jupiter.api.Test;\n\n"
            + "class EditorTest {\n"
            + "  @Test\n"
            + "  void text() {\n"
            + "    new Editor().text();\n"
            + "  }\n"
            + "}\n");
    write(
        root,
        "src/test/java/demo/util/StringsTest.java",
        "package demo.util;\n\n"
            + "import org.junit.jupiter.api.Test;\n\n"
            + "class StringsTest {\n"
            + "  @Test\n"
            + "  void blank() {\n"
            + "    Strings.blank(\"\");\n"
            + "  }\n"
            + "}\n");
    return root;
  }
}
