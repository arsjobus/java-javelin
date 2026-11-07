package text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextStatsTest {
  @Test
  void countsWordsSeparatedByAnyWhitespace() {
    assertEquals(4, TextStats.wordCount("one  two\tthree\nfour"));
  }

  @Test
  void leadingAndTrailingSpaceAddNoWords() {
    assertEquals(2, TextStats.wordCount("  hello world  "));
  }

  @Test
  void blankTextHasNoWords() {
    assertEquals(0, TextStats.wordCount(""));
    assertEquals(0, TextStats.wordCount("   \n "));
  }
}
