package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiUsage;
import com.javelin.ai.symbols.SymbolBudget;
import com.javelin.ai.symbols.SymbolConfig;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolContextTest {
  @TempDir Path dir;
  ProjectWorkspace ws;

  static final ContextBudget BUDGET = new ContextBudget(0, 32_768, 4_096);
  static final String REQUEST = "applyDiscount should reject a percent of 0";
  static final String CALC = "src/main/java/shop/PriceCalculator.java";
  static final String CHECKOUT = "src/main/java/shop/Checkout.java";
  static final String REPORTS = "src/main/java/shop/Reports.java";

  @BeforeEach
  void setUp() throws IOException {
    SymbolFixture.shop(dir);
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
  }

  private ProjectContext build(String request, SymbolOptions symbols) throws AiException {
    List<Pick> picks = SmartSelector.select(ws.index(), ws.reader(), request, ws.options());
    return ContextBuilder.build(
        ws.index(),
        ws.reader(),
        ContextMode.SMART,
        picks,
        Adjustments.NONE,
        BUDGET,
        100,
        ws.options(),
        1,
        symbols);
  }

  private ProjectContext symbolic(SymbolBudget budget) throws AiException {
    return build(REQUEST, new SymbolOptions(REQUEST, budget));
  }

  private static ContextEntry entry(ProjectContext c, String path) {
    return c.included().stream()
        .filter(e -> e.file().path().equals(path))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theMethodTheRequestIsAboutAndItsCallerAreSentInFullAndTheRestAsSignatures()
      throws AiException {
    ProjectContext c = symbolic(SymbolBudget.SMALL);
    String t = c.text();
    assertTrue(t.contains("if (percent < 0 || percent > 100) {"), "the target's body");
    assertTrue(
        t.contains("calculator.applyDiscount(subtotal, loyaltyPercent);"), "the caller's body");
    assertTrue(t.contains("/** Takes a percentage off a price. */"), "the target's own comment");
    assertFalse(t.contains("int a = x * 3;"), "the body of a method nobody asked about");
    assertTrue(t.contains("int rounding3(int x) " + SymbolSkeleton.PLACEHOLDER));
    assertTrue(t.contains("PriceCalculator.java (partial view)"), "the structure says so");
    assertTrue(t.contains("(java, partial view: "), "the file marker says so");
    assertTrue(t.contains(ContextBuilder.PARTIAL_NOTE));
    assertTrue(entry(c, CALC).isPartial());
    assertTrue(entry(c, CHECKOUT).isPartial());
  }

  @Test
  void aSymbolLevelContextCostsLessThanTheFileLevelOne() throws AiException {
    ProjectContext files = build(REQUEST, null);
    ProjectContext symbols = symbolic(SymbolBudget.SMALL);
    assertTrue(
        symbols.textTokens() < files.textTokens(),
        symbols.textTokens() + " should be less than " + files.textTokens());
    assertTrue(entry(symbols, CALC).tokens() < entry(files, CALC).tokens());
  }

  @Test
  void everySymbolIsSaidToBeShownInFullOnlyWhenItsTextIsInTheContext() throws AiException {
    ProjectContext c = symbolic(SymbolBudget.SMALL);
    List<ShownSymbol> shown = entry(c, CALC).symbols();
    ShownSymbol target =
        shown.stream()
            .filter(s -> s.name().equals("PriceCalculator.applyDiscount"))
            .findFirst()
            .orElseThrow();
    assertEquals(ShownSymbol.IN_FULL, target.how());
    assertEquals("target: named in the request: applyDiscount", target.why());
    ShownSymbol other =
        shown.stream()
            .filter(s -> s.name().equals("PriceCalculator.rounding1"))
            .findFirst()
            .orElseThrow();
    assertEquals(ShownSymbol.SIGNATURE, other.how());
    ShownSymbol caller =
        entry(c, CHECKOUT).symbols().stream()
            .filter(s -> s.name().equals("Checkout.total"))
            .findFirst()
            .orElseThrow();
    assertEquals(ShownSymbol.IN_FULL, caller.how());
    assertTrue(caller.why().startsWith("calls PriceCalculator.applyDiscount (line "), caller.why());
  }

  @Test
  void aViewHasNoLineThatIsNotInTheFileExceptThePlaceholdersAndNotes()
      throws AiException, IOException {
    ProjectContext c = symbolic(SymbolBudget.SMALL);
    for (ContextEntry e : c.partialEntries()) {
      Set<String> original = new HashSet<>();
      List<String> originalLines = Files.readAllLines(dir.resolve(e.file().path()));
      for (String l : originalLines) original.add(l.strip());
      String block = blockOf(c.text(), e.file().path());
      for (String line : block.lines().toList()) {
        String t = line.replace(SymbolSkeleton.PLACEHOLDER, "{").strip();
        if (t.isEmpty() || t.startsWith("// ")) continue;
        boolean found =
            original.contains(t) || originalLines.stream().anyMatch(o -> o.strip().startsWith(t));
        assertTrue(found, e.file().path() + ": '" + line + "' is not a line of the file");
      }
    }
  }

  private static String blockOf(String text, String path) {
    int begin = text.indexOf(" BEGIN FILE: " + path);
    int from = text.indexOf('\n', begin) + 1;
    int to = text.indexOf(" END FILE: " + path);
    return text.substring(from, text.lastIndexOf('\n', to));
  }

  @Test
  void theSameRequestGivesByteIdenticalTextAndSnapshot() throws AiException {
    ProjectContext a = symbolic(SymbolBudget.SMALL);
    ProjectContext b = symbolic(SymbolBudget.SMALL);
    assertEquals(a.text(), b.text());
    assertEquals(a.snapshot().id(), b.snapshot().id());
  }

  @Test
  void withoutSymbolOptionsNothingIsSentInPart() throws AiException {
    ProjectContext c = build(REQUEST, null);
    assertTrue(c.partialEntries().isEmpty());
    assertFalse(c.text().contains("partial view"));
    assertTrue(c.snapshot().partialPaths().isEmpty());
    assertTrue(c.notes().isEmpty());
  }

  @Test
  void theSnapshotRemembersWhichFilesWereSentInPartAndStillFingerprintsTheWholeFile()
      throws AiException {
    ProjectContext files = build(REQUEST, null);
    ProjectContext symbols = symbolic(SymbolBudget.SMALL);
    assertTrue(symbols.snapshot().partialPaths().contains(CALC));
    assertFalse(symbols.snapshot().partialPaths().contains("src/main/java/shop/Money.java"));
    assertEquals(
        files.snapshot().sentHashes().get(CALC), symbols.snapshot().sentHashes().get(CALC));
    assertNotEquals(files.snapshot().id(), symbols.snapshot().id());
  }

  @Test
  void aRequestThatNamesNoMethodSendsTheFilesWhole() throws AiException {
    String request = "describe the shop";
    ProjectContext c = build(request, new SymbolOptions(request, SymbolBudget.SMALL));
    assertTrue(c.partialEntries().isEmpty());
    assertEquals(
        List.of("No method name matches the request, so the files are sent whole."), c.notes());
  }

  @Test
  void aMethodWrittenAsOwnerDotMethodIsTheOnlyTargetEvenWhenOtherNamesMatchTheWords()
      throws AiException {
    String request = "Checkout.receipt should show the discount";
    ProjectContext c = build(request, new SymbolOptions(request, SymbolBudget.SMALL));
    ShownSymbol receipt =
        entry(c, CHECKOUT).symbols().stream()
            .filter(s -> s.name().equals("Checkout.receipt"))
            .findFirst()
            .orElseThrow();
    assertEquals(ShownSymbol.IN_FULL, receipt.how());
    assertEquals("target: named in the request as Checkout.receipt", receipt.why());
    assertTrue(
        entry(c, CALC).symbols().stream().noneMatch(ShownSymbol::isFull),
        "applyDiscount matches the word 'discount' but was not what the request named");
  }

  @Test
  void aWordOfTheRequestThatIsAMethodNameExactlyOutranksMethodsThatOnlyContainTheWord()
      throws AiException {
    String request = "withTax is wrong when the price is already discounted";
    ProjectContext c = build(request, new SymbolOptions(request, SymbolBudget.SMALL));
    ShownSymbol withTax =
        entry(c, CALC).symbols().stream()
            .filter(s -> s.name().equals("PriceCalculator.withTax"))
            .findFirst()
            .orElseThrow();
    assertEquals(ShownSymbol.IN_FULL, withTax.how());
    assertEquals("target: named in the request: withTax", withTax.why());
  }

  @Test
  void aConstructorIsATargetOnlyWhenTheRequestIsAboutConstructors() throws AiException {
    String about = "PriceCalculator price";
    ProjectContext none = build(about, new SymbolOptions(about, SymbolBudget.SMALL));
    assertTrue(
        none.partialEntries().isEmpty(), "the constructor's name matches every word of the type");
    String ctor = "the PriceCalculator constructor should reject a negative tax";
    ProjectContext c = build(ctor, new SymbolOptions(ctor, SymbolBudget.SMALL));
    assertTrue(
        entry(c, CALC).symbols().stream()
            .anyMatch(s -> s.name().equals("PriceCalculator.PriceCalculator") && s.isFull()));
  }

  // ---- profiles ----

  @Test
  void aProfileWhoseWholeFileThresholdIsLargerSendsTheseFilesWhole() throws AiException {
    ProjectContext c = symbolic(SymbolBudget.FRONTIER);
    assertTrue(c.partialEntries().isEmpty(), "every file is smaller than the frontier threshold");
  }

  @Test
  void aTargetLongerThanTheProfileSendsFallsBackToWholeFilesAndSaysWhy() throws AiException {
    SymbolBudget tiny = new SymbolBudget("tiny", 3, 20, 6, 30, 6, 1_500, 4_000, 400);
    ProjectContext c = symbolic(tiny);
    assertTrue(c.partialEntries().isEmpty());
    assertEquals(1, c.notes().size());
    assertTrue(c.notes().get(0).contains("too large for the tiny profile"), c.notes().toString());
  }

  @Test
  void aProfileWithNoRoomForCallersLeavesThemAsSignatures() throws AiException {
    SymbolBudget noCallers = new SymbolBudget("none", 3, 4_000, 6, 30, 0, 1_500, 4_000, 400);
    ProjectContext c = symbolic(noCallers);
    assertFalse(c.text().contains("calculator.applyDiscount(subtotal, loyaltyPercent);"));
    assertTrue(
        c.text().contains("if (percent < 0 || percent > 100) {"), "the target is still sent");
  }

  @Test
  void aCallerLongerThanTheProfileSendsIsListedBySignatureWithTheReason() throws AiException {
    SymbolBudget shortCallers = new SymbolBudget("short", 3, 4_000, 6, 30, 6, 40, 4_000, 400);
    ProjectContext c = symbolic(shortCallers);
    ShownSymbol total =
        entry(c, CHECKOUT).symbols().stream()
            .filter(s -> s.name().equals("Checkout.total"))
            .findFirst()
            .orElseThrow();
    assertEquals(ShownSymbol.SIGNATURE, total.how());
    assertTrue(total.why().contains("more than the short profile sends"), total.why());
    assertFalse(c.text().contains("calculator.applyDiscount(subtotal, loyaltyPercent);"));
  }

  @Test
  void theSymbolBudgetNeverTakesTheTargetAwayButHoldsBackWhatDoesNotFit() throws AiException {
    SymbolBudget starved = new SymbolBudget("starved", 3, 4_000, 6, 30, 6, 1_500, 1, 400);
    ProjectContext c = symbolic(starved);
    assertTrue(c.text().contains("if (percent < 0 || percent > 100) {"));
    ShownSymbol total =
        entry(c, CHECKOUT).symbols().stream()
            .filter(s -> s.name().equals("Checkout.total"))
            .findFirst()
            .orElseThrow();
    assertTrue(total.why().contains("the symbol budget is used up"), total.why());
  }

  @Test
  void aLongTypeListsOnlyTheSignaturesTheProfileAllowsAndSaysSo() throws AiException {
    SymbolBudget few = new SymbolBudget("few", 3, 4_000, 6, 5, 6, 1_500, 4_000, 400);
    ProjectContext c = symbolic(few);
    List<ShownSymbol> reports = entry(c, REPORTS).symbols();
    assertTrue(reports.stream().anyMatch(s -> s.how().equals(ShownSymbol.NOT_SHOWN)));
    assertTrue(c.text().contains("more member(s) not shown"));
  }

  // ---- what the user and the model are told ----

  private ProjectAiService service(
      boolean symbols, TokenCalibration calibration, ScriptedProvider p) throws IOException {
    Settings settings = new Settings(dir.resolveSibling("sym-settings-" + symbols + ".conf"));
    settings.put(Scope.USER, "ai.model", "m");
    if (symbols) settings.put(Scope.USER, SymbolConfig.ENABLED, "true");
    return new ProjectAiService(
        p,
        AiConfig.from(settings),
        ProjectAiConfig.from(settings),
        SymbolConfig.from(settings),
        calibration);
  }

  @Test
  void thePreviewNamesEverySymbolThatIsSentOfAFileSentInPart() throws Exception {
    ProjectPlan plan =
        service(true, new TokenCalibration(), new ScriptedProvider())
            .prepare(ws, ContextMode.SMART, REQUEST, Adjustments.NONE, new AiCancel());
    String preview = plan.preview();
    assertFalse(plan.context().partialEntries().isEmpty());
    for (ContextEntry e : plan.context().partialEntries())
      for (ShownSymbol s : e.symbols())
        assertTrue(preview.contains(s.name()), s.name() + " is sent but not in the preview");
    assertTrue(
        preview.contains("in full: PriceCalculator.applyDiscount - target: named in the request"));
    assertTrue(preview.contains("(partial view)"));
  }

  @Test
  void settingsDecideWhetherSmartContextWorksAtSymbolLevel() throws Exception {
    ProjectPlan off =
        service(false, new TokenCalibration(), new ScriptedProvider())
            .prepare(ws, ContextMode.SMART, REQUEST, Adjustments.NONE, new AiCancel());
    assertTrue(off.context().partialEntries().isEmpty());
    ProjectPlan full =
        service(true, new TokenCalibration(), new ScriptedProvider())
            .prepare(ws, ContextMode.FULL_PROJECT, REQUEST, Adjustments.NONE, new AiCancel());
    assertTrue(full.context().partialEntries().isEmpty(), "Full Project is never sent in part");
  }

  // ---- replies ----

  @Test
  void aWholeNewCopyOfAFileThatWasSentInPartIsRefusedButEditBlocksAreAccepted() throws Exception {
    ProjectContext c = symbolic(SymbolBudget.SMALL);
    String whole = "package shop;\n\npublic class PriceCalculator {\n}\n";
    ProjectProposal rewrite =
        MultiFileReply.parse(
            "Done.\n\n=== FILE: " + CALC + " ===\n```java\n" + whole + "```\n",
            ws.reader(),
            c.snapshot(),
            ws.options());
    assertTrue(rewrite.isEmpty(), rewrite.toString());
    assertTrue(rewrite.problems().get(0).contains("only in part"), rewrite.problems().toString());

    String edit =
        "<<<<<<< SEARCH\n    if (percent < 0 || percent > 100) {\n=======\n"
            + "    if (percent <= 0 || percent > 100) {\n>>>>>>> REPLACE\n";
    ProjectProposal ok =
        MultiFileReply.parse(
            "Reject zero.\n\n=== FILE: " + CALC + " ===\n" + edit,
            ws.reader(),
            c.snapshot(),
            ws.options());
    assertEquals(1, ok.changes().size(), ok.problems().toString());
    assertTrue(ok.changes().get(0).replacement().contains("percent <= 0"));
    assertTrue(
        ok.changes().get(0).replacement().contains("int rounding14(int x)"),
        "the unseen code stays");
  }

  @Test
  void aFileSentWholeCanStillBeRewrittenAsAWhole() throws Exception {
    ProjectContext c = build(REQUEST, null);
    ProjectProposal p =
        MultiFileReply.parse(
            "Done.\n\n"
                + "=== FILE: src/main/java/shop/Money.java ===\n"
                + "```java\n"
                + "package shop;\n\n"
                + "public record Money(long cents) {\n"
                + "}\n"
                + "```\n",
            ws.reader(),
            c.snapshot(),
            ws.options());
    assertEquals(1, p.changes().size(), p.problems().toString());
  }

  // ---- token calibration through the service ----

  @Test
  void theModelsOwnTokenCountsAdjustTheNextEstimate() throws Exception {
    TokenCalibration calibration = new TokenCalibration();
    ScriptedProvider provider = new ScriptedProvider();
    provider.answer = "Nothing to change.";
    ProjectAiService service = service(false, calibration, provider);
    ProjectPlan first =
        service.prepare(ws, ContextMode.SMART, REQUEST, Adjustments.NONE, new AiCancel());
    assertFalse(first.budget().isCalibrated());
    for (int i = 0; i < 2; i++) {
      ProjectPlan plan =
          service.prepare(ws, ContextMode.SMART, REQUEST, Adjustments.NONE, new AiCancel());
      provider.usage = new AiUsage(plan.request().size() / 4 + 32, 20);
      service.run(plan, ws, text -> {}, new AiCancel());
    }
    ProjectPlan adjusted =
        service.prepare(ws, ContextMode.SMART, REQUEST, Adjustments.NONE, new AiCancel());
    assertTrue(adjusted.budget().isCalibrated());
    assertTrue(adjusted.budget().charsPerToken() > ContextBudget.CHARS_PER_TOKEN);
    assertTrue(adjusted.context().textTokens() < first.context().textTokens());
    assertTrue(adjusted.preview().contains("adjusted to this model at 3.8 characters per token"));
  }
}
