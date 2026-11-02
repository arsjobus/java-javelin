package com.javelin.build;

import java.io.File;
import java.util.Optional;

/** Detects how a project is conventionally built, by looking at files in its root. */
public record BuildSystem(String name, String buildCommand, String suggestedRunCommand) {
    public static Optional<BuildSystem> detect(File projectDirectory) {
        if (projectDirectory == null) return Optional.empty();
        boolean windows = ShellCommand.isWindows();
        if (new File(projectDirectory, "pom.xml").isFile()) {
            String mvn = new File(projectDirectory, windows ? "mvnw.cmd" : "mvnw").isFile() ? (windows ? "mvnw.cmd" : "./mvnw") : "mvn";
            return Optional.of(new BuildSystem("Maven", mvn + " -B package", mvn + " -B javafx:run"));
        }
        boolean gradle = new File(projectDirectory, "build.gradle").isFile() || new File(projectDirectory, "build.gradle.kts").isFile();
        if (gradle) {
            String gw = new File(projectDirectory, windows ? "gradlew.bat" : "gradlew").isFile() ? (windows ? "gradlew.bat" : "./gradlew") : "gradle";
            return Optional.of(new BuildSystem("Gradle", gw + " build", gw + " run"));
        }
        if (new File(projectDirectory, "Makefile").isFile()) return Optional.of(new BuildSystem("Make", "make", "make run"));
        if (new File(projectDirectory, "Cargo.toml").isFile()) return Optional.of(new BuildSystem("Cargo", "cargo build", "cargo run"));
        if (new File(projectDirectory, "package.json").isFile()) return Optional.of(new BuildSystem("npm", "npm run build", "npm start"));
        return Optional.empty();
    }
}
