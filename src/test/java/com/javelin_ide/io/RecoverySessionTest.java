package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.io.RecoveryStore.Snapshot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code Javelin} starts one editor instance per project, and all of them share the recovery folder. These tests
 * check that an instance's live unsaved work is never offered to, or deleted by, another instance, and that it is
 * offered again once the instance that wrote it is gone, including when it was killed.
 */
class RecoverySessionTest {
    private static final Pattern SESSION_ID = Pattern.compile("s[0-9a-f]{12}-[0-9a-f-]{36}");

    @TempDir Path dir;
    Path folder;
    RecoveryStore first, second;

    @BeforeEach void setUp() {
        folder = dir.resolve("recovery");
        first = new RecoveryStore(folder);
        second = new RecoveryStore(folder);   // "another window" sharing the same folder
    }

    private static List<String> ids(List<Snapshot> snapshots) { return snapshots.stream().map(Snapshot::id).toList(); }

    private long lockFiles() throws IOException {
        try (Stream<Path> files = Files.list(folder)) { return files.filter(p -> p.getFileName().toString().startsWith(".session-")).count(); }
    }

    @Test void aLiveInstancesSnapshotsAreNotOfferedToAnotherInstance() throws IOException {
        first.startSession();
        first.saveIfChanged(first.newSessionId(), null, "unsaved in the first window");
        second.startSession();
        assertEquals(List.of(), second.pending(), "the second window must not offer the first window's work");
        assertEquals(List.of(), first.pending(), "nor does a window offer its own live work back to itself");
    }

    @Test void anotherInstanceCannotClearOrPurgeALiveInstancesSnapshots() throws IOException {
        first.startSession();
        String id = first.newSessionId();
        first.saveIfChanged(id, null, "still being edited");
        second.clear();                                       // what choosing Discard in the recovery prompt does
        assertEquals(0, second.purgeOlderThan(Duration.ZERO)); // even a purge of everything leaves it alone
        assertTrue(Files.isRegularFile(folder.resolve(id + ".recovery")), "the live snapshot must survive");
        assertEquals(1, lockFiles(), "and so must the lock that marks it live");
    }

    @Test void snapshotsOfAnEndedSessionAreOfferedAsLeftovers() throws IOException {
        first.startSession();
        String id = first.newSessionId();
        first.saveIfChanged(id, null, "left behind");
        first.endSession();
        List<Snapshot> found = second.pending();
        assertEquals(List.of(id), ids(found));
        assertEquals("left behind", found.get(0).text());
        assertEquals(0, lockFiles(), "an ended session leaves no lock file");
        second.clear();
        assertEquals(List.of(), second.pending(), "leftovers can be discarded once their instance is gone");
    }

    @Test void snapshotsWithoutASessionAreAlwaysLeftovers() throws IOException {
        first.startSession();
        first.saveIfChanged(RecoveryStore.newId(), null, "from a version that had no sessions");
        first.saveIfChanged("tab-1", null, "plain id");
        assertEquals(2, second.pending().size());
    }

    @Test void sessionIdsAreUniqueValidAndStableForTheSession() throws IOException {
        assertTrue(Pattern.matches("[0-9a-f-]{36}", first.newSessionId()), "without a session it is a plain id");
        first.startSession();
        first.startSession();                                 // a second call keeps the same session
        String a = first.newSessionId(), b = first.newSessionId();
        assertNotEquals(a, b);
        assertTrue(SESSION_ID.matcher(a).matches(), a);
        assertEquals(a.substring(0, 13), b.substring(0, 13), "one session, one prefix");
        assertEquals(1, lockFiles());
        second.startSession();
        assertNotEquals(a.substring(0, 13), second.newSessionId().substring(0, 13), "each instance has its own session");
        assertDoesNotThrow(() -> first.saveIfChanged(a, null, "ids are accepted by the store"));
    }

    @Test void aStoreNeverStartedHasNoSideEffects() throws IOException {
        first.endSession();                                   // harmless
        first.endSession();
        assertFalse(Files.exists(folder));
    }

    @Test void onlyOldLocksOfEndedSessionsAreSwept() throws IOException {
        first.startSession();                                 // live
        Files.writeString(folder.resolve(".session-aaaaaaaaaaaa.lock"), "");   // ended long ago
        Files.writeString(folder.resolve(".session-bbbbbbbbbbbb.lock"), "");   // ended just now, or just being created
        Files.setLastModifiedTime(folder.resolve(".session-aaaaaaaaaaaa.lock"), FileTime.from(Instant.now().minus(Duration.ofDays(40))));
        second.purgeOlderThan(Duration.ofDays(30));
        assertFalse(Files.exists(folder.resolve(".session-aaaaaaaaaaaa.lock")), "an old lock nobody holds is litter");
        assertTrue(Files.exists(folder.resolve(".session-bbbbbbbbbbbb.lock")), "a new lock file is never touched: its owner may be starting");
        assertEquals(2, lockFiles(), "the live instance's own lock stays");
    }

    /** What another Javelin IDE process does: lock a session, write unsaved work, then wait to be killed. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            RecoveryStore store = new RecoveryStore(Path.of(args[0]));
            store.startSession();
            store.saveIfChanged(store.newSessionId(), null, "unsaved in the other process");
            System.out.println("ready");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    @Test void aSeparateProcessIsRespectedWhileAliveAndItsWorkIsOfferedAfterItIsKilled() throws Exception {
        String classpath = codeSource(Holder.class) + java.io.File.pathSeparator + codeSource(RecoveryStore.class);
        Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, Holder.class.getName(), folder.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(other.getInputStream()));
            assertEquals("ready", CompletableFuture.supplyAsync(() -> {
                try { return out.readLine(); } catch (IOException e) { throw new IllegalStateException(e); }
            }).get(60, TimeUnit.SECONDS));

            second.startSession();
            assertEquals(List.of(), second.pending(), "the other process is alive: its work is not ours to offer");
            second.clear();
            try (Stream<Path> files = Files.list(folder)) {
                assertEquals(1, files.filter(p -> p.getFileName().toString().endsWith(".recovery")).count(), "and not ours to delete");
            }

            other.destroyForcibly().waitFor(30, TimeUnit.SECONDS);   // kill -9: no chance to clean up
            List<Snapshot> found = second.pending();
            assertEquals(1, found.size(), "a killed instance's unsaved work is offered like any crash");
            assertEquals("unsaved in the other process", found.get(0).text());
        } finally {
            other.destroyForcibly();
        }
    }

    private static String codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
