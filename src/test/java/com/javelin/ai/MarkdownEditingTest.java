package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Editing a Markdown file through the AI: a reply that wraps the file in a fence has fences inside
 * the fence, headings and bullets are text and not "placeholders", a line of {@code =} underlines a
 * heading and is not the divider of an edit block, and the language is known from the file's name.
 */
class MarkdownEditingTest {
  @TempDir Path dir;
  private FakeAiProvider provider;
  private AiService service;

  private static final String DOC =
      "# Guide\n\nHow to run it:\n\n```sh\nmvn test\n```\n\n## Other methods\n\nSee the manual.\n";

  @BeforeEach
  void setUp() {
    provider = new FakeAiProvider();
    Settings settings = new Settings(dir.resolve("settings.conf"));
    settings.put(Scope.USER, "ai.model", "fake-model");
    service = new AiService(provider, AiConfig.from(settings));
  }

  private AiResult propose(String fileName, String languageId, String text, String instruction)
      throws Exception {
    AiSource source =
        new AiSource("docs/" + fileName, fileName, languageId, text, 0, text.length());
    return service.run(
        service.prepare(AiAction.PROPOSE_CHANGE, source, instruction), s -> {}, new AiCancel());
  }

  // ---- reply parsing ----

  @Test
  void aWrappedFileKeepsTheFencesInsideIt() throws Exception {
    String reply = "Updated.\n\n```markdown\n" + DOC.replace("mvn test", "mvn verify") + "```\n";
    ResponseParser.Parsed parsed = ResponseParser.parse(reply, true);
    assertEquals(DOC.replace("mvn test", "mvn verify").stripTrailing(), parsed.code());
    assertEquals("Updated.", parsed.explanation());
  }

  @Test
  void withoutTheMarkdownFlagTheFirstInnerFenceStillEndsTheBlock() throws Exception {
    String reply = "```markdown\n" + DOC + "```\n";
    assertTrue(ResponseParser.parse(reply).code().endsWith("mvn test"));
  }

  @Test
  void aFourBacktickWrapperNeedsNoSpecialHandling() throws Exception {
    String reply = "````markdown\n" + DOC + "````\n";
    assertEquals(DOC.stripTrailing(), ResponseParser.parse(reply, false).code());
  }

  @Test
  void innerFencesThatOpenWithoutAWordAreStillInsideTheWrapper() throws Exception {
    String file = "# T\n\n```\nplain\n```\n\ntext\n";
    String reply = "```markdown\n" + file + "```\n";
    assertEquals(file.stripTrailing(), ResponseParser.parse(reply, true).code());
  }

  @Test
  void aWrapperThatNeverClosesIsStillTruncated() {
    AiException ex =
        assertThrows(
            AiException.class,
            () -> ResponseParser.parse("```markdown\n# T\n\nsome text without an end", true));
    assertEquals(FailureReason.TRUNCATED, ex.reason());
  }

  @Test
  void aPlainBlockInMarkdownModeIsUnchanged() throws Exception {
    assertEquals("a\nb", ResponseParser.parse("```\na\nb\n```\n\nnote\n", true).code());
  }

  // ---- edit blocks ----

  @Test
  void aHeadingUnderlineInTheSearchPartIsNotTheDivider() throws Exception {
    String original = "Install\n=======\nRun the installer.\n\nUse\n===\nStart it.\n";
    String reply =
        "<<<<<<< SEARCH\nInstall\n=======\nRun the installer.\n=======\n"
            + "Install\n=======\nRun the installer twice.\n>>>>>>> REPLACE\n";
    ChangeReply.Interpreted result =
        ChangeReply.interpret(reply, original, "README.md", "markdown");
    assertEquals(
        "Install\n=======\nRun the installer twice.\n\nUse\n===\nStart it.\n", result.code());
  }

  @Test
  void aReplyWithoutAnUnderlineKeepsTheFirstDivider() throws Exception {
    String original = "a\nb\n";
    String reply = "<<<<<<< SEARCH\na\n=======\nA\n=======\n>>>>>>> REPLACE\n";
    assertEquals(
        "A\n=======\nb\n", ChangeReply.interpret(reply, original, "x.md", "markdown").code());
  }

  // ---- checks ----

  @Test
  void aHeadingOrBulletThatSoundsLikeAPlaceholderIsText() {
    String after = DOC + "\n* the rest of the items follow\n## Remaining methods in the API\n";
    List<Finding> findings = ChangeChecker.check("markdown", DOC, after);
    assertTrue(findings.stream().noneMatch(Finding::isError), findings.toString());
  }

  @Test
  void aRealPlaceholderInMarkdownIsStillAnError() {
    String after = DOC + "\n<!-- ... rest of the document -->\n";
    assertTrue(ChangeChecker.check("markdown", DOC, after).stream().anyMatch(Finding::isError));
    assertTrue(
        ChangeChecker.check("markdown", DOC, DOC + "\n...\n").stream().anyMatch(Finding::isError));
  }

  @Test
  void aFenceThatIsOpenedAndNeverClosedIsAnError() {
    String after = DOC.replace("mvn test\n```\n", "mvn test\n");
    List<Finding> findings = ChangeChecker.check("markdown", DOC, after);
    assertTrue(
        findings.stream().anyMatch(f -> f.isError() && f.message().contains("never closed")),
        findings.toString());
  }

  @Test
  void aDocumentThatWasAlreadyUnbalancedIsNotBlamedOnTheEdit() {
    String broken = "# T\n\n```sh\nx\n";
    assertTrue(
        ChangeChecker.check("markdown", broken, broken + "more\n").stream()
            .noneMatch(Finding::isError));
  }

  @Test
  void lostFrontMatterIsAnError() {
    String before = "---\ntitle: x\n---\n# T\n";
    assertTrue(
        ChangeChecker.check("markdown", before, "# T\n\nmore\n").stream()
            .anyMatch(f -> f.isError() && f.message().contains("front matter")));
    assertTrue(
        ChangeChecker.check("markdown", before, "---\ntitle: y\n---\n# T\n").stream()
            .noneMatch(Finding::isError));
  }

  @Test
  void openFencesIgnoreInlineTripleBackticksAndLongerClosers() {
    assertEquals(0, MarkdownText.openFences("text ```not a fence``` text\n"));
    assertEquals(0, MarkdownText.openFences("```\ncode\n`````\n"));
    assertEquals(1, MarkdownText.openFences("````\ncode\n```\n"));
    assertEquals(0, MarkdownText.openFences("~~~\ncode\n~~~\n"));
  }

  // ---- the whole path ----

  @Test
  void aMarkdownFileIsChangedThroughTheWholePath() throws Exception {
    provider.answer =
        "Added a step.\n\n```markdown\n"
            + DOC.replace("```sh\nmvn test\n```", "```sh\nmvn test\n```\n\nThen open the report.")
            + "```\n";
    AiResult result = propose("GUIDE.md", "markdown", DOC, "add a step after the command");
    AiProposal proposal = result.proposal();
    assertNotNull(proposal, result.note());
    assertFalse(result.hasErrors(), result.findings().toString());
    assertTrue(proposal.applyTo(DOC).contains("Then open the report.\n\n## Other methods"));
    assertTrue(proposal.applyTo(DOC).contains("```sh\nmvn test\n```"));
  }

  @Test
  void theFileNameAloneIsEnoughToKnowItIsMarkdown() throws Exception {
    provider.answer = "```markdown\n" + DOC + "\nMore.\n```\n";
    AiResult result = propose("NOTES.md", null, DOC, "add a line");
    assertNotNull(result.proposal(), result.note());
    assertTrue(result.proposal().applyTo(DOC).contains("More."));
  }

  @Test
  void aMarkdownRequestCarriesTheMarkdownRules() throws Exception {
    provider.answer = "no";
    propose("GUIDE.md", "markdown", DOC, "change it");
    String sent = provider.requests.get(0).prompt();
    assertTrue(sent.contains("This is a Markdown file"), sent);
    assertTrue(sent.contains("FOUR backticks"));
  }

  @Test
  void aJavaRequestDoesNotCarryTheMarkdownRules() throws Exception {
    provider.answer = "no";
    propose("Foo.java", "java", "class Foo {}\n", "change it");
    assertFalse(provider.requests.get(0).prompt().contains("Markdown"));
  }

  @Test
  void theMarkdownRulesAreLeftOutOfOtherActions() throws Exception {
    provider.answer = "It explains the guide.";
    AiSource source = new AiSource("GUIDE.md", "GUIDE.md", "markdown", DOC, 0, DOC.length());
    service.run(service.prepare(AiAction.EXPLAIN, source, null), s -> {}, new AiCancel());
    assertFalse(provider.requests.get(0).prompt().contains("FOUR backticks"));
  }
}
