package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.SourceOutline;
import com.javelin.ai.SourceOutline.Kind;
import com.javelin.ai.SourceOutline.Member;
import com.javelin.ai.SourceOutline.Outline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SymbolSkeletonTest {
  private static final String SOURCE =
      """
      package demo;

      import java.util.List;

      public class Box {
        @Deprecated static final int OLD = 1;
        private static final List<String> NAMES =
            List.of(
                "a",
                "b");
        Path left, right;

        static {
          init();
        }

        @SuppressWarnings(
            "unused")
        public int size() {
          return 1;
        }

        @Override public String toString() { return "box"; }

        abstract int hook(int x);

        enum Color {
          RED,
          GREEN("g");
          Color() {}
          Color(String s) {}
          int rank() { return 2; }
        }

        record Pair(int a, int b) {
          Pair {
            if (a < 0) throw new IllegalArgumentException();
          }
          int sum() { return a + b; }
        }
      }
      """;

  private static SymbolSkeleton.Rendered render(Set<Integer> full) {
    Outline o = SourceOutline.of(SOURCE);
    return SymbolSkeleton.render(SOURCE, o, full, Map.of(), 100);
  }

  @Test
  void everyKindOfMemberIsInTheOutlineSoTheViewCanBeTrusted() {
    Outline o = SourceOutline.of(SOURCE);
    assertTrue(o.isComplete(), "first unplaced line: " + o.firstUnrecognised());
    List<String> names = o.members().stream().map(Member::qualified).toList();
    for (String n :
        List.of(
            "Box",
            "Box.OLD",
            "Box.NAMES",
            "Box.left",
            "Box.<static>",
            "Box.size",
            "Box.toString",
            "Box.hook",
            "Box.Color",
            "Box.Color.<constants>",
            "Box.Color.rank",
            "Box.Pair",
            "Box.Pair.Pair",
            "Box.Pair.sum")) assertTrue(names.contains(n), n + " missing from " + names);
    Member compact =
        o.members().stream().filter(m -> m.qualified().equals("Box.Pair.Pair")).findFirst().get();
    assertEquals(Kind.CONSTRUCTOR, compact.kind());
  }

  @Test
  void aViewKeepsDeclarationsFieldsConstantsAndAnnotationsAndHidesOnlyBodies() {
    String view = render(Set.of()).text();
    assertTrue(view.contains("@Deprecated static final int OLD = 1;"));
    assertTrue(view.contains("List.of(\n"), "a multi-line initializer is kept");
    assertTrue(view.contains("Path left, right;"));
    assertTrue(view.contains("static " + SymbolSkeleton.PLACEHOLDER));
    assertTrue(view.contains("@SuppressWarnings(\n      \"unused\")\n"), "annotations over lines");
    assertTrue(view.contains("public int size() " + SymbolSkeleton.PLACEHOLDER));
    assertTrue(view.contains("@Override public String toString() " + SymbolSkeleton.PLACEHOLDER));
    assertTrue(view.contains("abstract int hook(int x);"), "no body, nothing to hide");
    assertTrue(view.contains("RED,\n"), "the constants of an enum");
    assertTrue(view.contains("GREEN(\"g\");"));
    assertTrue(view.contains("Pair " + SymbolSkeleton.PLACEHOLDER));
    assertFalse(view.contains("init();"));
    assertFalse(view.contains("return a + b;"));
    assertFalse(view.contains("IllegalArgumentException"));
  }

  @Test
  void typesAreClosedAgainAtTheirIndentation() {
    String view = render(Set.of()).text();
    assertEquals(
        count(SOURCE, '{') - count(SOURCE, '}'),
        count(view.replace(SymbolSkeleton.PLACEHOLDER, ""), '{')
            - count(view.replace(SymbolSkeleton.PLACEHOLDER, ""), '}'),
        "the view is as balanced as the file");
    assertTrue(view.contains("\n  enum Color {\n"));
    assertTrue(view.contains("\n  }\n"), "an inner type is closed at its own indentation");
    assertTrue(view.trim().endsWith("}"));
  }

  private static long count(String s, char c) {
    return s.chars().filter(x -> x == c).count();
  }

  @Test
  void aMemberShownInFullKeepsItsBodyItsAnnotationsAndItsComment() {
    Outline o = SourceOutline.of(SOURCE);
    Member size = o.members().stream().filter(m -> m.name().equals("size")).findFirst().get();
    String view = render(Set.of(size.first())).text();
    assertTrue(view.contains("public int size() {\n    return 1;\n  }"));
    assertTrue(view.contains("@SuppressWarnings(\n"));
    assertEquals(1, count(view, '1') - count(view.replace("return 1;", ""), '1'));
  }

  @Test
  void everyMemberIsReportedAsShownOrNotShown() {
    SymbolSkeleton.Rendered r = render(Set.of());
    Outline o = SourceOutline.of(SOURCE);
    assertEquals(o.members().size(), r.shown().size());
    assertTrue(r.shown().stream().noneMatch(ShownSymbol::isFull));
  }

  @Test
  void aTypeWithMoreMembersThanTheProfileListsSaysHowManyAreMissing() {
    Outline o = SourceOutline.of(SOURCE);
    SymbolSkeleton.Rendered r = SymbolSkeleton.render(SOURCE, o, Set.of(), Map.of(), 2);
    assertTrue(r.text().contains("more member(s) not shown"));
    assertTrue(r.shown().stream().anyMatch(s -> s.how().equals(ShownSymbol.NOT_SHOWN)));
  }

  // ---- the same properties over every Java file of this project ----

  @Test
  void onEveryJavaFileOfThisProjectAViewHidesNothingButBodies() throws IOException {
    List<String> problems = new ArrayList<>();
    int checked = 0;
    for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
      List<Path> files;
      try (Stream<Path> walk = Files.walk(root)) {
        files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      }
      for (Path file : files) {
        String text = Files.readString(file);
        Outline o = SourceOutline.of(text);
        if (!o.isComplete()) continue; // such a file is sent whole; see the coverage test below
        checked++;
        String view = SymbolSkeleton.render(text, o, Set.of(), Map.of(), Integer.MAX_VALUE).text();
        List<String> viewLines = view.lines().map(String::strip).toList();
        List<String> orig = text.lines().toList();
        for (Member m : o.members()) {
          int to =
              m.kind() == Kind.TYPE || m.isCallable() || m.kind() == Kind.INITIALIZER
                  ? (m.kind() == Kind.TYPE ? m.open() : m.open() - 1)
                  : m.last();
          if (!m.hasBody() && m.kind() != Kind.FIELD) to = m.last();
          for (int n = m.first(); n <= to; n++) {
            String line = orig.get(n - 1).strip();
            if (line.isEmpty()
                || line.startsWith("//")
                || line.startsWith("/*")
                || line.startsWith("*")) continue;
            if (!viewLines.contains(line))
              problems.add(file + ":" + n + " '" + line + "' (" + m.qualified() + ")");
          }
        }
      }
    }
    assertTrue(checked > 100, "only " + checked + " files were checked");
    assertEquals(List.of(), problems.stream().limit(10).toList());
  }

  @Test
  void theOutlineReadsAlmostEveryJavaFileOfThisProjectExactly() throws IOException {
    int total = 0;
    int incomplete = 0;
    List<String> names = new ArrayList<>();
    for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
      try (Stream<Path> walk = Files.walk(root)) {
        for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
          total++;
          if (!SourceOutline.of(Files.readString(file)).isComplete()) {
            incomplete++;
            names.add(file.getFileName().toString());
          }
        }
      }
    }
    assertTrue(incomplete * 20 <= total, incomplete + " of " + total + " are not exact: " + names);
  }
}
