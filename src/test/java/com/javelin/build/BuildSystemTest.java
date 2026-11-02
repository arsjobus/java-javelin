package com.javelin.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildSystemTest {
    @TempDir Path dir;

    private void touch(String name) throws IOException { Files.writeString(dir.resolve(name), ""); }

    private BuildSystem detect() { return BuildSystem.detect(dir.toFile()).orElseThrow(); }

    @Test void nullAndEmptyDirectoriesAreNotBuildable() {
        assertTrue(BuildSystem.detect(null).isEmpty());
        assertTrue(BuildSystem.detect(dir.toFile()).isEmpty());
        assertTrue(BuildSystem.detect(new File(dir.toFile(), "missing")).isEmpty());
    }

    @Test void mavenAndItsWrapper() throws IOException {
        touch("pom.xml");
        assertEquals(new BuildSystem("Maven", "mvn -B package", "mvn -B javafx:run"), detect());
        touch(ShellCommand.isWindows() ? "mvnw.cmd" : "mvnw");
        assertEquals(ShellCommand.isWindows() ? "mvnw.cmd -B package" : "./mvnw -B package", detect().buildCommand());
    }

    @Test void gradleBothDslsAndWrapper() throws IOException {
        touch("build.gradle.kts");
        assertEquals(new BuildSystem("Gradle", "gradle build", "gradle run"), detect());
        touch("build.gradle");
        touch(ShellCommand.isWindows() ? "gradlew.bat" : "gradlew");
        assertEquals(ShellCommand.isWindows() ? "gradlew.bat build" : "./gradlew build", detect().buildCommand());
    }

    @Test void makeCargoNpm() throws IOException {
        touch("Makefile");
        assertEquals("make", detect().buildCommand());
        Files.delete(dir.resolve("Makefile"));
        touch("Cargo.toml");
        assertEquals("cargo build", detect().buildCommand());
        Files.delete(dir.resolve("Cargo.toml"));
        touch("package.json");
        assertEquals(new BuildSystem("npm", "npm run build", "npm start"), detect());
    }

    @Test void mavenWinsOverEverythingElse() throws IOException {
        for (String f : new String[] {"pom.xml", "build.gradle", "Makefile", "Cargo.toml", "package.json"}) touch(f);
        assertEquals("Maven", detect().name());
    }

    @Test void aDirectoryNamedLikeABuildFileDoesNotCount() throws IOException {
        Files.createDirectory(dir.resolve("pom.xml"));
        assertTrue(BuildSystem.detect(dir.toFile()).isEmpty());
    }
}
