package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VocabularyTest {
  @Test
  void wordsSplitCamelCaseDigitsAndPunctuation() {
    assertEquals(List.of("diagram", "renderer"), Vocabulary.words("DiagramRenderer"));
    assertEquals(List.of("html", "parser"), Vocabulary.words("HTMLParser"));
    assertEquals(List.of("my", "var", "name"), Vocabulary.words("my_var.name"));
    assertEquals(List.of("src", "main", "java"), Vocabulary.words("src/main/java"));
    assertEquals(List.of("utf"), Vocabulary.words("utf8")); // one character is no word
    assertTrue(Vocabulary.words("a b c").isEmpty());
  }

  @Test
  void stemmingJoinsPluralsAndEndings() {
    assertEquals("diagram", Vocabulary.stem("diagrams"));
    assertEquals("query", Vocabulary.stem("queries"));
    assertEquals("box", Vocabulary.stem("boxes"));
    assertEquals("class", Vocabulary.stem("class"));
    assertEquals("status", Vocabulary.stem("status"));
    assertEquals("pars", Vocabulary.stem("parsing"));
    assertEquals("pars", Vocabulary.stem("parsed"));
    assertEquals("go", Vocabulary.stem("go"));
    assertEquals("watch", Vocabulary.stem("watches"));
  }

  @Test
  void requestsBecomeTermsWithoutFillerAndWithRelatedWordsAtHalfWeight() {
    List<Vocabulary.Term> terms = Vocabulary.terms("Add a UML generator to the editor.");
    assertTrue(terms.contains(new Vocabulary.Term("uml", 1.0)));
    assertTrue(terms.contains(new Vocabulary.Term("generator", 1.0)));
    assertTrue(terms.contains(new Vocabulary.Term("editor", 1.0)));
    assertTrue(terms.contains(new Vocabulary.Term("diagram", 0.5)));
    assertTrue(terms.contains(new Vocabulary.Term("generate", 0.5)));
    assertFalse(terms.stream().anyMatch(t -> t.word().equals("add") || t.word().equals("the")));
    assertEquals(terms.size(), terms.stream().map(Vocabulary.Term::word).distinct().count());
  }

  @Test
  void aWordTheUserWroteKeepsFullWeightEvenIfAlsoRelatedToAnother() {
    List<Vocabulary.Term> terms = Vocabulary.terms("uml diagram");
    assertEquals(
        1.0,
        terms.stream().filter(t -> t.word().equals("diagram")).findFirst().orElseThrow().weight());
  }

  @Test
  void relatedWordsShareTheirBeginning() {
    assertTrue(Vocabulary.related("diagram", "diagram"));
    assertTrue(Vocabulary.related("edit", "editor"));
    assertTrue(Vocabulary.related("generator", "generate"));
    assertTrue(Vocabulary.related("parser", "parse"));
    assertFalse(Vocabulary.related("por", "portal"));
    assertFalse(Vocabulary.related("test", "contest"));
    assertFalse(Vocabulary.related("editor", "creator"));
    assertFalse(Vocabulary.related("ab", "abc"));
  }
}
