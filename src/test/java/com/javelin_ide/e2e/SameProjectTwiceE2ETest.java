package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.launch.ProjectInstances;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Running {@code javelin} twice in one folder: the second window must find out that the first one has the project,
 * and must be able to have it once the first window is gone, however that window ended. The "first window" is a real
 * separate process, because the lock that carries this between windows is an operating-system lock.
 */
class SameProjectTwiceE2ETest {
    @TempDir Path home;

    /** What the first javelin IDE window does: claim the project, then stay open until killed. */
    public static final class FirstWindow {
        public static void main(String[] args) throws Exception {
            ProjectInstances.Claim claim = new ProjectInstances(Path.of(args[0])).claim(Path.of(args[1])).orElseThrow();
            System.out.println("open " + claim.project());
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    private Process startFirstWindow(Path config, Path project) throws Exception {
        String classpath = codeSource(FirstWindow.class) + java.io.File.pathSeparator + codeSource(ProjectInstances.class);
        Process window = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, FirstWindow.class.getName(), config.toString(), project.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(window.getInputStream()));
        String line = CompletableFuture.supplyAsync(() -> {
            try { return out.readLine(); } catch (IOException e) { throw new IllegalStateException(e); }
        }).get(60, TimeUnit.SECONDS);
        assertEquals("open " + project.toRealPath(), line);
        return window;
    }

    @Test void aSecondWindowIsToldTheProjectIsOpenUntilTheFirstWindowIsGone() throws Exception {
        Path config = home.resolve("config").resolve(ProjectInstances.DIRECTORY_NAME);
        Path project = Files.createDirectories(home.resolve("demo"));
        Process first = startFirstWindow(config, project);
        try {
            ProjectInstances second = new ProjectInstances(config);
            assertTrue(second.claim(project).isEmpty(), "the first window has it");
            assertTrue(second.claim(home.resolve("x").resolve("..").resolve("demo")).isEmpty(), "however the folder is spelled");
            try (ProjectInstances.Claim other = second.claim(Files.createDirectories(home.resolve("other"))).orElseThrow()) {
                assertNotNull(other, "a different project is free");
            }

            String real = project.toRealPath().toString();
            Path lock;
            try (Stream<Path> files = Files.list(config)) { lock = files.filter(p -> p.toString().endsWith(".lock") && read(p).trim().equals(real)).findFirst().orElseThrow(); }
            Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minus(Duration.ofDays(90))));
            assertEquals(0, second.prune(Duration.ofDays(30)), "housekeeping never takes a lock from a live window");
            assertTrue(Files.exists(lock));

            first.destroyForcibly().waitFor(30, TimeUnit.SECONDS); // kill -9: no chance to clean up
            try (ProjectInstances.Claim claim = second.claim(project).orElseThrow()) {
                assertEquals(project.toRealPath(), claim.project(), "a crashed window never leaves a project stuck");
            }
        } finally {
            first.destroyForcibly();
        }
    }

    private static String read(Path file) {
        try { return Files.readString(file); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static String codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
