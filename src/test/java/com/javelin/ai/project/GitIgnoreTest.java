package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitIgnoreTest {
  private static GitIgnore rules(String... lines) {
    return GitIgnore.parse(List.of(lines), "");
  }

  @Test
  void aNameWithoutASlashMatchesAtAnyDepth() {
    GitIgnore g = rules("*.log", "notes.txt");
    assertEquals(true, g.verdict("debug.log", false));
    assertEquals(true, g.verdict("a/b/debug.log", false));
    assertEquals(true, g.verdict("deep/notes.txt", false));
    assertNull(g.verdict("debug.logx", false));
  }

  @Test
  void aTrailingSlashMatchesOnlyFolders() {
    GitIgnore g = rules("build/");
    assertEquals(true, g.verdict("build", true));
    assertEquals(true, g.verdict("module/build", true));
    assertNull(g.verdict("build", false));
  }

  @Test
  void aSlashInsideAnchorsToTheFolderOfTheFile() {
    GitIgnore g = rules("docs/*.md", "/top.txt");
    assertEquals(true, g.verdict("docs/a.md", false));
    assertNull(g.verdict("x/docs/a.md", false));
    assertNull(g.verdict("docs/sub/a.md", false));
    assertEquals(true, g.verdict("top.txt", false));
    assertNull(g.verdict("sub/top.txt", false));
  }

  @Test
  void doubleStarsSpanFolders() {
    GitIgnore g = rules("a/**/z.txt", "logs/**", "**/gen/*.java");
    assertEquals(true, g.verdict("a/z.txt", false));
    assertEquals(true, g.verdict("a/b/c/z.txt", false));
    assertEquals(true, g.verdict("logs/x/y.txt", false));
    assertEquals(true, g.verdict("m/gen/A.java", false));
    assertEquals(true, g.verdict("gen/A.java", false));
    assertNull(g.verdict("m/gen/sub/A.java", false));
  }

  @Test
  void questionMarkAndCharacterClasses() {
    GitIgnore g = rules("file?.txt", "[abc].md", "x[!0-9].c");
    assertEquals(true, g.verdict("file1.txt", false));
    assertNull(g.verdict("file10.txt", false));
    assertEquals(true, g.verdict("b.md", false));
    assertNull(g.verdict("d.md", false));
    assertEquals(true, g.verdict("xa.c", false));
    assertNull(g.verdict("x5.c", false));
    assertNull(rules("odd[.txt").verdict("other.txt", false));
    assertEquals(true, rules("odd[.txt").verdict("odd[.txt", false));
  }

  @Test
  void theLastMatchingRuleWinsAndABangTakesAFileBack() {
    GitIgnore g = rules("*.log", "!keep.log");
    assertEquals(true, g.verdict("a.log", false));
    assertEquals(false, g.verdict("keep.log", false));
    GitIgnore again = rules("!keep.log", "*.log");
    assertEquals(true, again.verdict("keep.log", false));
  }

  @Test
  void commentsBlankLinesAndEscapedCharactersAreHandled() {
    GitIgnore g =
        rules("# a comment", "", "   ", "\\#hash.txt", "\\!bang.txt", "trailing.txt   ", "/");
    assertNull(g.verdict("# a comment", false));
    assertEquals(true, g.verdict("#hash.txt", false));
    assertEquals(true, g.verdict("!bang.txt", false));
    assertEquals(true, g.verdict("trailing.txt", false));
    assertFalse(g.isEmpty());
    assertTrue(rules("# only a comment").isEmpty());
  }

  @Test
  void rulesOfANestedFileApplyOnlyBelowIt() {
    GitIgnore nested = GitIgnore.parse(List.of("*.tmp", "/local.txt"), "sub/dir");
    assertEquals(true, nested.verdict("sub/dir/a.tmp", false));
    assertEquals(true, nested.verdict("sub/dir/deeper/a.tmp", false));
    assertEquals(true, nested.verdict("sub/dir/local.txt", false));
    assertNull(nested.verdict("sub/dir/deeper/local.txt", false));
    assertNull(nested.verdict("other/a.tmp", false));
    assertNull(nested.verdict("sub/dirx/a.tmp", false));
  }

  @Test
  void regexCharactersInNamesAreLiteral() {
    GitIgnore g = rules("a+b(1).txt", "dot.name", "back\\*star");
    assertEquals(true, g.verdict("a+b(1).txt", false));
    assertNull(g.verdict("aab(1).txt", false));
    assertNull(g.verdict("dotxname", false));
    assertEquals(true, g.verdict("back*star", false));
    assertNull(g.verdict("backXstar", false));
  }

  @Test
  void starsThatAreNotWholeSegmentsStayInsideASegment() {
    GitIgnore g = rules("a**b", "c/**d");
    assertEquals(true, g.verdict("axxb", false));
    assertNull(g.verdict("a/b", false));
    assertEquals(true, g.verdict("c/xd", false));
  }
}
