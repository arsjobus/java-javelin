package text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PalindromeTest {
  @Test
  void ignoresCaseAndPunctuation() {
    assertTrue(Palindrome.isPalindrome("A man, a plan, a canal: Panama"));
  }

  @Test
  void plainPalindromesStillWork() {
    assertTrue(Palindrome.isPalindrome("racecar"));
    assertTrue(Palindrome.isPalindrome(""));
  }

  @Test
  void otherTextIsNot() {
    assertFalse(Palindrome.isPalindrome("hello"));
    assertFalse(Palindrome.isPalindrome("ab, ac"));
  }
}
