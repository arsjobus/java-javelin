package web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlugifierTest {
  @Test
  void lowersAndJoinsWithDashes() {
    assertEquals("hello-world", Slugifier.slugify("Hello World"));
  }

  @Test
  void runsOfOtherCharactersBecomeOneDash() {
    assertEquals("a-b", Slugifier.slugify("a !!  b"));
  }

  @Test
  void noDashAtTheEnds() {
    assertEquals("x", Slugifier.slugify("--x--"));
  }
}
