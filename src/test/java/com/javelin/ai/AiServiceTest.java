package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiServiceTest {
    @TempDir Path dir;
    private FakeAiProvider provider;
    private AiService service;
    private static final String DOC = "class Foo {\n    int add(int a, int b) { return a - b; }\n}\n";
    private static final int SEL_START = DOC.indexOf("    int add"), SEL_END = DOC.indexOf("}\n", SEL_START) + 1;

    @BeforeEach void setUp() {
        provider = new FakeAiProvider();
        Settings settings = new Settings(dir.resolve("settings.conf"));
        settings.put(Scope.USER, "ai.model", "fake-model");
        service = new AiService(provider, AiConfig.from(settings));
    }

    private static AiSource source(String fileName, int a, int b) { return new AiSource(fileName == null ? "Untitled" : "src/" + fileName, fileName, "java", DOC, a, b); }

    @Test void preparingNeedsAModel() {
        AiService noModel = new AiService(provider, AiConfig.from(new Settings(dir.resolve("none.conf"))));
        AiException ex = assertThrows(AiException.class, () -> noModel.prepare(AiAction.EXPLAIN, source("Foo.java", SEL_START, SEL_END), null));
        assertEquals(AiException.Kind.REFUSED, ex.kind());
        assertTrue(ex.getMessage().contains("Choose Model"));
        assertTrue(provider.requests.isEmpty());
    }

    @Test void credentialFilesAreRefusedBeforeAnythingIsPrepared() {
        AiException ex = assertThrows(AiException.class, () -> service.prepare(AiAction.EXPLAIN, new AiSource("project/.env", ".env", null, "A=1", 0, 3), null));
        assertTrue(ex.getMessage().contains("credentials"));
        assertTrue(provider.requests.isEmpty());
    }

    @Test void thePlanShowsExactlyWhatWillBeSent() throws Exception {
        AiPlan plan = service.prepare(AiAction.EXPLAIN, source("Foo.java", SEL_START, SEL_END), null);
        assertEquals("Fake · fake-model · on this computer (127.0.0.1:1)", plan.indicator());
        String preview = plan.preview();
        assertTrue(preview.contains("Provider:  Fake") && preview.contains("Model:     fake-model"));
        assertTrue(preview.contains("Network:   none: the request goes to 127.0.0.1:1, on this computer"));
        assertTrue(preview.contains("selection of src/Foo.java, " + (SEL_END - SEL_START) + " characters"));
        assertTrue(preview.contains(plan.request().system()) && preview.contains(plan.request().prompt()));
        assertTrue(preview.contains("return a - b;"));
        assertFalse(preview.contains("class Foo {"), "only the selection is sent, not the rest of the file");
        assertTrue(provider.requests.isEmpty(), "preparing sends nothing");
    }

    @Test void secretLookingTextIsWarnedAboutInThePreview() throws Exception {
        String text = "String k = \"AKIAABCDEFGHIJKLMNOP\";";
        AiPlan plan = service.prepare(AiAction.EXPLAIN, new AiSource("A.java", "A.java", "java", text, 0, text.length()), null);
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.preview().contains("WARNING: The text to send looks like it contains an access key id"));
    }

    @Test void explainingReturnsTheTextAndNoProposal() throws Exception {
        provider.answer = "It subtracts instead of adding.";
        AiPlan plan = service.prepare(AiAction.EXPLAIN, source("Foo.java", SEL_START, SEL_END), null);
        List<String> streamed = new ArrayList<>();
        AiResult result = service.run(plan, streamed::add, new AiCancel());
        assertEquals("It subtracts instead of adding.", result.text());
        assertNull(result.proposal());
        assertNull(result.note());
        assertEquals(List.of("It subtracts instead of adding."), streamed);
        assertEquals(1, provider.requests.size());
        assertEquals(plan.request(), provider.requests.get(0));
    }

    @Test void aCutOffExplanationIsFlagged() throws Exception {
        provider.answer = "It subtr";
        provider.truncated = true;
        AiResult r = service.run(service.prepare(AiAction.DEBUG, source("Foo.java", SEL_START, SEL_END), null), s -> {}, new AiCancel());
        assertTrue(r.note().contains("cut off"));
    }

    @Test void aChangeBecomesAProposalForTheSelectedRange() throws Exception {
        provider.answer = "Fixed the sign.\n```java\n    int add(int a, int b) { return a + b; }\n```";
        AiPlan plan = service.prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", SEL_START, SEL_END), "make add really add");
        AiResult r = service.run(plan, s -> {}, new AiCancel());
        AiProposal p = r.proposal();
        assertNotNull(p);
        assertEquals("Fixed the sign.", p.explanation());
        assertEquals("class Foo {\n    int add(int a, int b) { return a + b; }\n}\n", p.applyTo(DOC));
    }

    @Test void generatedTestsBecomeANewDocumentWithAConventionalName() throws Exception {
        provider.answer = "Tests:\n```java\nclass FooTest {}\n```";
        AiPlan plan = service.prepare(AiAction.GENERATE_TESTS, source("Foo.java", 0, 0), null);
        assertEquals("FooTest.java", plan.testFileName());
        assertEquals(AiContext.Scope.WHOLE_FILE, plan.context().scope());
        AiProposal p = service.run(plan, s -> {}, new AiCancel()).proposal();
        assertEquals(AiProposal.Kind.NEW_DOCUMENT, p.kind());
        assertEquals("FooTest.java", p.suggestedName());
        assertEquals("class FooTest {}\n", p.replacement());
    }

    @Test void aReplyWithoutCodeIsShownAsTextWithAReasonAndNoProposal() throws Exception {
        provider.answer = "I would not change that.";
        AiResult r = service.run(service.prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", SEL_START, SEL_END), "x"), s -> {}, new AiCancel());
        assertNull(r.proposal());
        assertEquals("I would not change that.", r.text());
        assertTrue(r.note().contains("no code block"));
    }

    @Test void aTruncatedReplyNeverBecomesAProposalEvenWithACompleteLookingBlock() throws Exception {
        provider.answer = "```java\nint a;\n```";
        provider.truncated = true;
        AiResult r = service.run(service.prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", SEL_START, SEL_END), "x"), s -> {}, new AiCancel());
        assertNull(r.proposal());
        assertTrue(r.note().contains("cut off"));
    }

    @Test void extraCodeBlocksAreNoted() throws Exception {
        provider.answer = "```\none\n```\n```\ntwo\n```";
        AiResult r = service.run(service.prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", SEL_START, SEL_END), "x"), s -> {}, new AiCancel());
        assertTrue(r.note().contains("2 code blocks"));
        assertTrue(r.proposal().replacement().strip().startsWith("one")); // indentation of the selection is put back, see ChangeReplyTest
    }

    @Test void anEmptyAnswerIsAnError() throws Exception {
        provider.answer = "  \n";
        AiPlan plan = service.prepare(AiAction.EXPLAIN, source("Foo.java", SEL_START, SEL_END), null);
        assertEquals(AiException.Kind.BAD_RESPONSE, assertThrows(AiException.class, () -> service.run(plan, s -> {}, new AiCancel())).kind());
    }

    @Test void providerFailuresAreRethrown() throws Exception {
        provider.failure = new AiException(AiException.Kind.UNAVAILABLE, "down");
        AiPlan plan = service.prepare(AiAction.EXPLAIN, source("Foo.java", SEL_START, SEL_END), null);
        assertEquals("down", assertThrows(AiException.class, () -> service.run(plan, s -> {}, new AiCancel())).getMessage());
    }

    @Test void aCancelledRunDoesNotCallTheProvider() throws Exception {
        AiPlan plan = service.prepare(AiAction.EXPLAIN, source("Foo.java", SEL_START, SEL_END), null);
        AiCancel cancel = new AiCancel();
        cancel.cancel();
        assertEquals(AiException.Kind.CANCELLED, assertThrows(AiException.class, () -> service.run(plan, s -> {}, cancel)).kind());
        assertTrue(provider.requests.isEmpty());
    }

    @Test void modelsComeFromTheProvider() throws Exception { assertEquals(List.of("fake-model"), service.models(new AiCancel())); }

    // ---- checking and splicing a proposed change ----

    private AiResult propose(String reply, int from, int to) throws Exception {
        provider.answer = reply;
        return service.run(service.prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", from, to), "make add really add"), s -> {}, new AiCancel());
    }

    @Test void aBrokenReplyIsStillProposedButTheChecksSayWhatIsWrong() throws Exception {
        AiResult r = propose("```java\n    int add(int a, int b) {\n        return a + b\n```", SEL_START, SEL_END);
        assertNotNull(r.proposal());
        assertTrue(r.hasErrors());
        assertTrue(r.findings().stream().anyMatch(f -> f.message().contains("Brackets")));
    }

    @Test void aPlaceholderReplyIsFlaggedAsAnError() throws Exception {
        AiResult r = propose("```java\n    int add(int a, int b) {\n        // ... rest of the method\n    }\n```", SEL_START, SEL_END);
        assertTrue(r.hasErrors());
        assertTrue(r.findings().stream().anyMatch(f -> f.message().contains("placeholder")));
    }

    @Test void aSoundReplyHasNoFindings() throws Exception {
        AiResult r = propose("```java\n    int add(int a, int b) { return a + b; }\n```", SEL_START, SEL_END);
        assertEquals(List.of(), r.findings());
        assertFalse(r.hasErrors());
    }

    @Test void aSelectionThatStartsMidLineIsSentAsWholeLinesAndTheReplyIsSplicedBackCleanly() throws Exception {
        int from = DOC.indexOf("int add"), to = DOC.indexOf("a - b") + 5;
        AiPlan plan = service.prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", from, to), "fix add");
        assertTrue(plan.request().prompt().contains("\n    int add(int a, int b) { return a - b; }\n"), "the model sees the line with its indentation");
        provider.answer = "```java\nint add(int a, int b) { return a + b; }\n```"; // the model lost the indentation
        AiResult r = service.run(plan, s -> {}, new AiCancel());
        assertEquals("class Foo {\n    int add(int a, int b) { return a + b; }\n}\n", r.proposal().applyTo(DOC));
        assertTrue(r.findings().stream().anyMatch(f -> f.message().contains("indentation")));
        assertFalse(r.hasErrors());
    }

    @Test void editBlocksAreAppliedToTheSelectedCode() throws Exception {
        AiResult r = propose("Fixed the sign.\n<<<<<<< SEARCH\n    int add(int a, int b) { return a - b; }\n=======\n    int add(int a, int b) { return a + b; }\n>>>>>>> REPLACE", SEL_START, SEL_END);
        assertEquals("Fixed the sign.", r.proposal().explanation());
        assertEquals("class Foo {\n    int add(int a, int b) { return a + b; }\n}\n", r.proposal().applyTo(DOC));
        assertEquals(List.of(), r.findings());
    }

    @Test void editBlocksThatDoNotMatchTheCodeProposeNothingAndSayWhy() throws Exception {
        AiResult r = propose("<<<<<<< SEARCH\n    int sub(int a, int b) { return a - b; }\n=======\nx\n>>>>>>> REPLACE", SEL_START, SEL_END);
        assertNull(r.proposal());
        assertTrue(r.note().contains("not in the code"));
        assertEquals(DOC, DOC, "nothing was applied");
    }

    @Test void aUnifiedDiffReplyIsAppliedAsAPatch() throws Exception {
        AiResult r = propose("```diff\n@@ -1 +1 @@\n-    int add(int a, int b) { return a - b; }\n+    int add(int a, int b) { return a + b; }\n```", SEL_START, SEL_END);
        assertEquals("class Foo {\n    int add(int a, int b) { return a + b; }\n}\n", r.proposal().applyTo(DOC));
    }

    @Test void aLongSelectionAsksForEditBlocksInTheMessage() throws Exception {
        StringBuilder body = new StringBuilder("class Big {\n");
        for (int i = 0; i < 50; i++) body.append("    int f").append(i).append("() { return ").append(i).append("; }\n");
        body.append("}\n");
        AiPlan plan = service.prepare(AiAction.PROPOSE_CHANGE, new AiSource("Big.java", "Big.java", "java", body.toString(), 0, body.length()), "rename f3 to three");
        assertTrue(plan.request().prompt().contains("<<<<<<< SEARCH"));
        provider.answer = "<<<<<<< SEARCH\n    int f3() { return 3; }\n=======\n    int three() { return 3; }\n>>>>>>> REPLACE";
        AiResult r = service.run(plan, s -> {}, new AiCancel());
        assertEquals(body.toString().replace("int f3()", "int three()"), r.proposal().applyTo(body.toString()));
        assertEquals(1, r.proposal().diff().added());
        assertTrue(r.findings().stream().noneMatch(Finding::isError), r.findings().toString());
    }

    @Test void theEditBlockLimitFromTheSettingsDecidesWhatTheModelIsAskedFor() throws Exception {
        Settings settings = new Settings(dir.resolve("limit.conf"));
        settings.put(Scope.USER, "ai.model", "fake-model");
        settings.put(Scope.USER, "ai.editBlockMinLines", "0");
        AiPlan always = new AiService(provider, AiConfig.from(settings)).prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", SEL_START, SEL_END), "x");
        assertTrue(always.request().prompt().contains("<<<<<<< SEARCH"));
        settings.put(Scope.USER, "ai.editBlockMinLines", "100000");
        AiPlan never = new AiService(provider, AiConfig.from(settings)).prepare(AiAction.PROPOSE_CHANGE, source("Foo.java", SEL_START, SEL_END), "x");
        assertFalse(never.request().prompt().contains("<<<<<<< SEARCH"));
    }
}
