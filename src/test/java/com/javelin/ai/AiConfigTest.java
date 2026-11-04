package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiConfigTest {
    @TempDir Path dir;
    private Settings settings;

    @BeforeEach void setUp() { settings = new Settings(dir.resolve("settings.conf")); }

    @Test void defaultsSendNothingAnywhereAndChooseNoModel() {
        AiConfig c = AiConfig.from(settings);
        assertEquals("ollama", c.provider());
        assertEquals("http://127.0.0.1:11434", c.endpoint());
        assertFalse(c.hasModel());
        assertEquals(12_000, c.maxContextChars());
        assertEquals(Duration.ofSeconds(120), c.timeout());
        assertTrue(AiConfig.validate(settings).isEmpty());
    }

    @Test void valuesAreReadAndClamped() {
        settings.put(Scope.USER, "ai.model", "  qwen2.5-coder:7b ");
        settings.put(Scope.USER, "ai.provider", "OLLAMA");
        settings.put(Scope.USER, "ai.maxContextChars", "1");
        settings.put(Scope.USER, "ai.timeoutSeconds", "999999");
        AiConfig c = AiConfig.from(settings);
        assertEquals("qwen2.5-coder:7b", c.model());
        assertEquals("ollama", c.provider());
        assertEquals(AiConfig.MIN_MAX_CONTEXT_CHARS, c.maxContextChars());
        assertEquals(Duration.ofSeconds(AiConfig.MAX_TIMEOUT_SECONDS), c.timeout());
        assertEquals(2, AiConfig.validate(settings).size());
    }

    @Test void badValuesFallBackAndAreReported() {
        settings.put(Scope.USER, "ai.maxContextChars", "lots");
        settings.put(Scope.USER, "ai.provider", "skynet");
        settings.put(Scope.USER, "ai.ollama.endpoint", "http://example.com:11434");
        assertEquals(12_000, AiConfig.from(settings).maxContextChars());
        var problems = AiConfig.validate(settings);
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("ai.provider") && p.contains("ollama")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("ai.ollama.endpoint") && p.contains("this computer")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("ai.maxContextChars")));
    }

    @Test void aProjectCannotChooseTheProviderTheModelOrTheAddress() throws Exception {
        Path project = dir.resolve("project");
        Files.createDirectories(project.resolve(Settings.PROJECT_DIRECTORY));
        Files.writeString(project.resolve(Settings.PROJECT_DIRECTORY).resolve(Settings.FILE_NAME),
            "ai.ollama.endpoint=http://evil.example:1\nai.model=evil\nai.provider=evil\nai.maxContextChars=200000\n");
        settings.setProject(project);
        for (String key : new String[] {"ai.model", "ai.ollama.endpoint", "ai.provider", "ai.maxContextChars", "ai.timeoutSeconds"}) {
            assertTrue(Settings.isUserOnly(key), key);
            assertThrows(IllegalArgumentException.class, () -> settings.put(Scope.PROJECT, key, "x"), key);
        }
        AiConfig c = AiConfig.from(settings);
        assertEquals("http://127.0.0.1:11434", c.endpoint());
        assertEquals("ollama", c.provider());
        assertEquals(12_000, c.maxContextChars());
        assertFalse(c.hasModel());
    }

    @Test void withModelChangesOnlyTheModel() {
        AiConfig c = AiConfig.from(settings).withModel(" m ");
        assertEquals("m", c.model());
        assertEquals(12_000, c.maxContextChars());
        assertEquals("", c.withModel(null).model());
    }

    @Test void providersAreCreatedByIdAndUnknownOnesRefused() throws Exception {
        assertEquals("ollama", AiProviders.create(AiConfig.from(settings)).id());
        settings.put(Scope.USER, "ai.provider", "skynet");
        assertEquals(AiException.Kind.REFUSED, assertThrows(AiException.class, () -> AiProviders.create(AiConfig.from(settings))).kind());
        settings.put(Scope.USER, "ai.provider", "ollama");
        settings.put(Scope.USER, "ai.ollama.endpoint", "http://example.com");
        assertEquals(AiException.Kind.REFUSED, assertThrows(AiException.class, () -> AiProviders.create(AiConfig.from(settings))).kind());
    }
}
