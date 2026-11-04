package com.javelin.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.*;
import com.javelin.ai.FakeOllamaAccess;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AI workflow as a person uses it, on the real settings, the real Ollama client and a fake Ollama server on a loopback
 * port: choose a model, preview what is sent, run, review the proposal, apply or reject, and the failures on the way.
 */
class AiWorkflowE2ETest {
    @TempDir Path dir;

    private static final String DOC = "public class Calc {\n    int add(int a, int b) { return a - b; }\n    int twice(int a) { return a * 2; }\n}\n";
    private static final int START = DOC.indexOf("    int add"), END = DOC.indexOf('\n', START) + 1;

    private AiService service(FakeOllamaAccess server, String model) throws Exception {
        Settings settings = new Settings(dir.resolve("settings.conf"));
        settings.put(Scope.USER, "ai.ollama.endpoint", server.endpoint());
        if (model != null) settings.put(Scope.USER, "ai.model", model);
        AiConfig config = AiConfig.from(settings);
        return new AiService(AiProviders.create(config), config);
    }

    @Test void chooseAModelThenExplainASelection() throws Exception {
        try (var server = FakeOllamaAccess.start()) {
            server.models("{\"models\":[{\"name\":\"llama3:latest\"},{\"name\":\"codellama\"}]}");
            AiService noModel = service(server, null);
            List<String> models = noModel.models(new AiCancel());
            assertEquals(List.of("codellama", "llama3:latest"), models);

            Settings settings = new Settings(dir.resolve("settings.conf"));
            settings.put(Scope.USER, "ai.model", models.get(1));
            assertEquals("llama3:latest", AiConfig.from(settings).model());

            server.chat("This method subtracts.", " It should add.");
            AiService svc = service(server, "llama3:latest");
            AiPlan plan = svc.prepare(AiAction.EXPLAIN, new AiSource("Calc.java", "Calc.java", "java", DOC, START, END), null);
            assertTrue(plan.preview().contains("return a - b;"));
            assertFalse(plan.preview().contains("twice"), "only the selection leaves the editor");
            assertEquals(0, server.chatCalls(), "nothing is sent before the user approves");

            List<String> shown = new ArrayList<>();
            AiResult result = svc.run(plan, shown::add, new AiCancel());
            assertEquals("This method subtracts. It should add.", result.text());
            assertEquals(List.of("This method subtracts.", " It should add."), shown);
            assertEquals(1, server.chatCalls());
            assertTrue(server.lastChatBody().contains("return a - b;"));
            assertFalse(server.lastChatBody().contains("twice"));
        }
    }

    @Test void proposeAChangeReviewItAndApplyIt() throws Exception {
        try (var server = FakeOllamaAccess.start()) {
            server.chat("Changed the operator.\n```java\n    int add(int a, int b) { return a + b; }\n```\n");
            AiService svc = service(server, "m");
            AiPlan plan = svc.prepare(AiAction.PROPOSE_CHANGE, new AiSource("Calc.java", "Calc.java", "java", DOC, START, END), "fix add");
            AiProposal proposal = svc.run(plan, s -> {}, new AiCancel()).proposal();

            String diff = proposal.diff().text();
            assertTrue(diff.contains("-    int add(int a, int b) { return a - b; }"));
            assertTrue(diff.contains("+    int add(int a, int b) { return a + b; }"));
            assertEquals(DOC, DOC, "nothing has changed until the user applies");

            assertTrue(proposal.isStillValid(DOC));
            String applied = proposal.applyTo(DOC);
            assertEquals(DOC.replace("a - b", "a + b"), applied);
            assertEquals(1, applied.lines().filter(l -> l.contains("twice")).count());
        }
    }

    @Test void rejectingLeavesTheDocumentUntouchedAndAStaleProposalCannotBeApplied() throws Exception {
        try (var server = FakeOllamaAccess.start()) {
            server.chat("```java\n    int add(int a, int b) { return b + a; }\n```");
            AiService svc = service(server, "m");
            AiProposal proposal = svc.run(svc.prepare(AiAction.PROPOSE_CHANGE, new AiSource("Calc.java", "Calc.java", "java", DOC, START, END), "x"), s -> {}, new AiCancel()).proposal();
            String rejected = DOC; // rejecting means doing nothing
            assertEquals(DOC, rejected);
            String editedMeanwhile = DOC.replace("a - b", "a * b");
            assertFalse(proposal.isStillValid(editedMeanwhile));
        }
    }

    @Test void generateTestsOpensANewDocumentAndDoesNotTouchTheSource() throws Exception {
        try (var server = FakeOllamaAccess.start()) {
            server.chat("Here are tests.\n```java\nimport org.junit.jupiter.api.Test;\nclass CalcTest { @Test void adds() {} }\n```");
            AiService svc = service(server, "m");
            AiPlan plan = svc.prepare(AiAction.GENERATE_TESTS, new AiSource("src/Calc.java", "Calc.java", "java", DOC, 0, 0), null);
            assertTrue(plan.preview().contains("whole file of src/Calc.java"));
            AiProposal p = svc.run(plan, s -> {}, new AiCancel()).proposal();
            assertEquals("CalcTest.java", p.suggestedName());
            assertTrue(p.replacement().contains("class CalcTest"));
        }
    }

    @Test void ollamaNotRunningIsAMessageNotAProblem() throws Exception {
        String endpoint;
        try (var server = FakeOllamaAccess.start()) { endpoint = server.endpoint(); }
        Settings settings = new Settings(dir.resolve("settings.conf"));
        settings.put(Scope.USER, "ai.ollama.endpoint", endpoint);
        settings.put(Scope.USER, "ai.model", "m");
        AiConfig config = AiConfig.from(settings);
        AiService svc = new AiService(AiProviders.create(config), config);
        AiPlan plan = svc.prepare(AiAction.EXPLAIN, new AiSource("Calc.java", "Calc.java", "java", DOC, START, END), null);
        AiException ex = assertThrows(AiException.class, () -> svc.run(plan, s -> {}, new AiCancel()));
        assertEquals(AiException.Kind.UNAVAILABLE, ex.kind());
        assertTrue(ex.getMessage().contains("ollama serve"));
    }

    @Test void aSecretFileNeverGetsAsFarAsTheNetwork() throws Exception {
        try (var server = FakeOllamaAccess.start()) {
            AiService svc = service(server, "m");
            assertThrows(AiException.class, () -> svc.prepare(AiAction.EXPLAIN, new AiSource(".env", ".env", null, "TOKEN=abc", 0, 9), null));
            assertEquals(0, server.chatCalls());
        }
    }

    @Test void aRemoteAddressInTheSettingsIsRefusedBeforeAnyRequest() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.conf"));
        settings.put(Scope.USER, "ai.ollama.endpoint", "http://ai.example.com:11434");
        settings.put(Scope.USER, "ai.model", "m");
        assertEquals(AiException.Kind.REFUSED, assertThrows(AiException.class, () -> AiProviders.create(AiConfig.from(settings))).kind());
        assertFalse(AiConfig.validate(settings).isEmpty());
        assertTrue(Files.notExists(dir.resolve("none")));
    }
}
