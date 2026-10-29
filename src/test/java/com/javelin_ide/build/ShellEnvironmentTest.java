package com.javelin_ide.build;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.build.ShellEnvironment.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShellEnvironmentTest {
    private static final Map<String, String> LAUNCHD = Map.of("PATH", "/usr/bin:/bin:/usr/sbin:/sbin", "HOME", "/Users/me", "TERM", "dumb");

    private static String envOutput(String... entries) {
        return ShellEnvironment.BEGIN + String.join("\0", entries) + "\0" + ShellEnvironment.END;
    }

    private static ShellEnvironment.Resolved resolve(ShellEnvironment.Probe probe) {
        return ShellEnvironment.resolve(LAUNCHD, "/bin/zsh", false, probe, p -> false);
    }

    @Test void loginShellPathAndToolVariablesAreUsed() {
        var r = resolve((cmd, t) -> envOutput("PATH=/opt/homebrew/bin:/usr/bin:/bin", "JAVA_HOME=/jdk21", "HOME=/Users/me"));
        assertEquals(Source.LOGIN_SHELL, r.source());
        assertEquals("/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin", r.variables().get("PATH"));
        assertEquals("/jdk21", r.variables().get("JAVA_HOME"));
    }

    @Test void loginShellIsRunAsLoginAndInteractiveSoBothProfileAndRcFilesApply() {
        List<String>[] seen = new List[1];
        resolve((cmd, t) -> { seen[0] = cmd; return envOutput("PATH=/x"); });
        assertEquals("/bin/zsh", seen[0].get(0));
        assertEquals(List.of("-l", "-i", "-c"), seen[0].subList(1, 4));
        assertTrue(seen[0].get(4).contains("env -0"));
    }

    @Test void bannersPrintedByRcFilesAreIgnored() {
        var r = resolve((cmd, t) -> "Welcome back!\nnvm loaded\n" + envOutput("PATH=/nvm/bin:/usr/bin") + "trailing noise");
        assertEquals(Source.LOGIN_SHELL, r.source());
        assertTrue(r.variables().get("PATH").startsWith("/nvm/bin:/usr/bin"));
    }

    @Test void valuesWithNewlinesAndEqualsSignsSurvive() {
        Map<String, String> vars = ShellEnvironment.parse(envOutput("A=line1\nline2", "B=x=y", "=bad", "noequals"));
        assertEquals("line1\nline2", vars.get("A"));
        assertEquals("x=y", vars.get("B"));
        assertEquals(2, vars.size());
    }

    @Test void sessionDetailsOfTheLoginShellAreNotCopied() {
        var r = resolve((cmd, t) -> envOutput("PATH=/a", "PWD=/tmp", "SHLVL=3", "_=/usr/bin/env", "TERM=xterm", "OLDPWD=/q"));
        for (String name : Set.of("PWD", "SHLVL", "_", "OLDPWD")) assertFalse(r.variables().containsKey(name), name);
        assertEquals("dumb", r.variables().get("TERM"));
    }

    @Test void inheritedVariablesTheShellDoesNotSetAreKept() {
        var r = resolve((cmd, t) -> envOutput("PATH=/a"));
        assertEquals("/Users/me", r.variables().get("HOME"));
    }

    @Test void pathEntriesAreNotDuplicated() {
        assertEquals("/a:/b:/c", ShellEnvironment.joinPaths("/a:/b", "/b::/c:/a"));
    }

    @Test void aFailingShellFallsBackToInheritedPathPlusExistingToolDirs() {
        var r = ShellEnvironment.resolve(LAUNCHD, "/bin/zsh", false, (cmd, t) -> { throw new java.io.IOException("no shell"); },
            p -> p.equals(Path.of("/opt/homebrew/bin")) || p.equals(Path.of("/usr/local/bin")));
        assertEquals(Source.FALLBACK, r.source());
        assertEquals("/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin", r.variables().get("PATH"));
    }

    @Test void aShellThatPrintsNothingUsableFallsBack() {
        assertEquals(Source.FALLBACK, resolve((cmd, t) -> "").source());
        assertEquals(Source.FALLBACK, resolve((cmd, t) -> "zsh: command not found: env").source());
        assertEquals(Source.FALLBACK, resolve((cmd, t) -> envOutput("JAVA_HOME=/x")).source()); // no PATH: not trustworthy
    }

    @Test void aMissingPathStillGetsASaneDefault() {
        var r = ShellEnvironment.resolve(Map.of(), null, false, (cmd, t) -> { throw new RuntimeException(); }, p -> false);
        assertEquals("/usr/bin:/bin:/usr/sbin:/sbin", r.variables().get("PATH"));
    }

    @Test void aBlankShellDefaultsToZsh() {
        String[] first = new String[1];
        ShellEnvironment.resolve(LAUNCHD, " ", false, (cmd, t) -> { first[0] = cmd.get(0); return ""; }, p -> false);
        assertEquals("/bin/zsh", first[0]);
    }

    @Test void windowsKeepsTheInheritedEnvironmentWithoutStartingAShell() {
        var r = ShellEnvironment.resolve(LAUNCHD, "cmd", true, (cmd, t) -> { throw new AssertionError("must not run"); }, p -> true);
        assertEquals(Source.INHERITED, r.source());
        assertEquals(LAUNCHD, r.variables());
    }

    @Test void resolvedMapIsReadOnly() {
        var r = resolve((cmd, t) -> envOutput("PATH=/a"));
        assertThrows(UnsupportedOperationException.class, () -> r.variables().put("X", "y"));
    }

    @Test void applyPutsTheResolvedEnvironmentOnAProcessBuilder() {
        ShellEnvironment.reset();
        ProcessBuilder pb = ShellEnvironment.apply(new ProcessBuilder("true"));
        assertEquals(ShellEnvironment.path(), pb.environment().get("PATH"));
        assertFalse(ShellEnvironment.path().isBlank());
        assertNotNull(ShellEnvironment.source());
    }
}
