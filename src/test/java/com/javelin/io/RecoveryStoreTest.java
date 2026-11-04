package com.javelin.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.io.RecoveryStore.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryStoreTest {
  @TempDir Path dir;
  private RecoveryStore store;

  @BeforeEach
  void setUp() {
    store = new RecoveryStore(dir.resolve("recovery"));
  }

  @Test
  void nothingIsPendingBeforeAnythingIsSaved() {
    assertTrue(store.pending().isEmpty());
    assertFalse(Files.exists(store.directory()), "an unused store creates nothing on disk");
    store.clear();
    assertEquals(0, store.purgeOlderThan(Duration.ZERO));
  }

  @Test
  void aSnapshotSurvivesTheStoreThatWroteIt() throws IOException {
    Path original = dir.resolve("My Project/Main.java");
    String text = "class A {\n\n  // unsaved \uD83D\uDE00\r\n}";
    assertTrue(store.saveIfChanged("tab-1", original, text));
    List<Snapshot> found = new RecoveryStore(dir.resolve("recovery")).pending(); // a "next session"
    assertEquals(1, found.size());
    assertEquals("tab-1", found.get(0).id());
    assertEquals(original.toAbsolutePath(), found.get(0).original());
    assertEquals(text, found.get(0).text());
    assertTrue(Duration.between(found.get(0).savedAt(), Instant.now()).abs().toMinutes() < 1);
  }

  @Test
  void untitledDocumentsHaveNoOriginal() throws IOException {
    store.saveIfChanged("u", null, "scratch");
    assertNull(store.pending().get(0).original());
  }

  @Test
  void identicalTextIsNotRewritten() throws IOException {
    assertTrue(store.saveIfChanged("a", null, "v1"));
    FileTimeProbe probe = new FileTimeProbe(store.directory().resolve("a.recovery"));
    assertFalse(store.saveIfChanged("a", null, "v1"));
    assertTrue(probe.unchanged());
    assertTrue(store.saveIfChanged("a", null, "v2"));
    assertEquals("v2", store.pending().get(0).text());
  }

  @Test
  void aDeletedSnapshotIsRewrittenEvenIfTheTextIsUnchanged() throws IOException {
    store.saveIfChanged("a", null, "v1");
    Files.delete(store.directory().resolve("a.recovery"));
    assertTrue(store.saveIfChanged("a", null, "v1"));
  }

  @Test
  void discardRemovesOneSnapshotAndToleratesUnknownOnes() throws IOException {
    store.saveIfChanged("a", null, "1");
    store.saveIfChanged("b", null, "2");
    store.discard("a");
    store.discard("never-existed");
    assertEquals(List.of("b"), store.pending().stream().map(Snapshot::id).toList());
    assertTrue(
        store.saveIfChanged("a", null, "1"), "after a discard the same text is written again");
  }

  @Test
  void clearRemovesEverythingButForeignFiles() throws IOException {
    store.saveIfChanged("a", null, "1");
    store.saveIfChanged("b", null, "2");
    Path foreign = store.directory().resolve("notes.txt");
    Files.writeString(foreign, "mine");
    store.clear();
    assertTrue(store.pending().isEmpty());
    assertTrue(Files.exists(foreign));
  }

  @Test
  void pendingIsOrderedOldestFirst() throws Exception {
    store.saveIfChanged("zzz", null, "first");
    Thread.sleep(15);
    store.saveIfChanged("aaa", null, "second");
    assertEquals(List.of("zzz", "aaa"), store.pending().stream().map(Snapshot::id).toList());
  }

  @Test
  void damagedSnapshotsAreSkippedNotDeleted() throws IOException {
    store.saveIfChanged("good", null, "fine");
    Path folder = store.directory();
    Files.writeString(folder.resolve("empty.recovery"), "");
    Files.writeString(folder.resolve("nomagic.recovery"), "hello\nworld\n\nbody");
    Files.writeString(
        folder.resolve("noblank.recovery"), "javelin-ide-recovery 1\noriginal=\nsaved=1\nbytes=0");
    Files.writeString(
        folder.resolve("shortheader.recovery"), "javelin-ide-recovery 1\noriginal=\n\nbody");
    Files.writeString(
        folder.resolve("badnumber.recovery"),
        "javelin-ide-recovery 1\noriginal=\nsaved=x\nbytes=4\n\nbody");
    Files.writeString(
        folder.resolve("badlength.recovery"),
        "javelin-ide-recovery 1\noriginal=\nsaved=1\nbytes=99\n\nbody");
    Files.writeString(
        folder.resolve("badorder.recovery"),
        "javelin-ide-recovery 1\nsaved=1\noriginal=\nbytes=4\n\nbody");
    Files.writeString(
        folder.resolve("baduri.recovery"),
        "javelin-ide-recovery 1\noriginal=not a uri\nsaved=1\nbytes=4\n\nbody");
    Files.write(folder.resolve("binary.recovery"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0});
    Files.createDirectory(folder.resolve("dir.recovery"));
    assertEquals(List.of("good"), store.pending().stream().map(Snapshot::id).toList());
    try (var files = Files.list(folder)) {
      assertEquals(11, files.count(), "damaged files stay for a human to inspect");
    }
  }

  @Test
  void tamperedByteCountsAreDetected() throws IOException {
    store.saveIfChanged("a", null, "h\u00e9llo");
    Path f = store.directory().resolve("a.recovery");
    Files.writeString(
        f, Files.readString(f).replace("h\u00e9llo", "hello"), StandardCharsets.UTF_8);
    assertTrue(store.pending().isEmpty());
  }

  @Test
  void idsCannotEscapeTheDirectory() {
    for (String bad :
        new String[] {null, "", "../evil", "a/b", "a\\b", "a.b", "a b", "x".repeat(65)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> store.saveIfChanged(bad, null, "x"),
          String.valueOf(bad));
      assertThrows(IllegalArgumentException.class, () -> store.discard(bad), String.valueOf(bad));
    }
    assertDoesNotThrow(() -> store.saveIfChanged(RecoveryStore.newId(), null, "ok"));
    assertNotEquals(RecoveryStore.newId(), RecoveryStore.newId());
  }

  @Test
  void purgeRemovesOnlyOldSnapshots() throws IOException {
    store.saveIfChanged("old", null, "1");
    store.saveIfChanged("new", null, "2");
    Files.writeString(store.directory().resolve("damaged.recovery"), "junk");
    Files.setLastModifiedTime(
        store.directory().resolve("old.recovery"),
        FileTime.from(Instant.now().minus(Duration.ofDays(40))));
    Files.setLastModifiedTime(
        store.directory().resolve("damaged.recovery"),
        FileTime.from(Instant.now().minus(Duration.ofDays(40))));
    assertEquals(2, store.purgeOlderThan(Duration.ofDays(30)));
    assertEquals(List.of("new"), store.pending().stream().map(Snapshot::id).toList());
  }

  @Test
  void theDirectoryIsPrivateWhereSupported() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    store.saveIfChanged("a", null, "secret");
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(store.directory())));
  }

  @Test
  void aStoreDirectoryThatIsAFileFailsLoudlyOnSaveButQuietlyOnRead() throws IOException {
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "i am a file");
    RecoveryStore broken = new RecoveryStore(blocker);
    assertThrows(IOException.class, () -> broken.saveIfChanged("a", null, "x"));
    assertTrue(broken.pending().isEmpty());
    broken.clear();
    broken.discard("a");
    assertEquals(0, broken.purgeOlderThan(Duration.ZERO));
  }

  @Test
  void largeBuffersRoundTrip() throws IOException {
    String text = "a line of unsaved work\n".repeat(200_000);
    store.saveIfChanged("big", null, text);
    assertEquals(text, store.pending().get(0).text());
  }

  @Test
  void unwritableSnapshotFilesAreLeftForTheNextAttempt() throws IOException {
    store.saveIfChanged("a", null, "x");
    Path f = store.directory().resolve("a.recovery");
    Files.delete(f);
    Files.createDirectory(f); // cannot be deleted as a file, cannot be read as a snapshot
    Files.writeString(f.resolve("child"), "x");
    store.discard("a"); // must not throw
    store.clear();
    assertTrue(store.pending().isEmpty());
  }

  @Test
  void anUnreadableFolderMeansNothingIsPending() throws IOException {
    store.saveIfChanged("a", null, "x");
    RecoveryStore unreadable =
        new RecoveryStore(
            store.directory(),
            true,
            d -> {
              throw new IOException("permission denied");
            });
    assertTrue(unreadable.pending().isEmpty());
    unreadable.clear();
    assertEquals(1, store.pending().size(), "nothing was deleted because nothing could be listed");
  }

  @Test
  void fileSystemsWithoutPosixPermissionsStillWork() throws IOException {
    RecoveryStore plain = new RecoveryStore(dir.resolve("plain"), false, Files::list);
    plain.saveIfChanged("a", null, "no permissions API");
    assertEquals("no permissions API", plain.pending().get(0).text());
  }

  // ---- cursor position and tab order (BUGS.md: crash recovery used to restore only the text) ----

  @Test
  void theCursorPositionSurvivesTheStoreThatWroteIt() throws IOException {
    store.saveIfChanged("a", null, "line one\nline two", 12, 3);
    Snapshot found = new RecoveryStore(dir.resolve("recovery")).pending().get(0);
    assertEquals(12, found.caret());
    assertEquals(3, found.tabIndex());
    assertEquals("line one\nline two", found.text());
  }

  @Test
  void movingOnlyTheCursorOrTheTabIsAChangeButRepeatingThemIsNot() throws IOException {
    assertTrue(store.saveIfChanged("a", null, "text", 1, 0));
    assertFalse(store.saveIfChanged("a", null, "text", 1, 0));
    assertTrue(store.saveIfChanged("a", null, "text", 2, 0), "cursor moved");
    assertTrue(store.saveIfChanged("a", null, "text", 2, 1), "tab moved");
    assertEquals(2, store.pending().get(0).caret());
    assertEquals(1, store.pending().get(0).tabIndex());
  }

  @Test
  void theThreeArgumentSaveStillWorksAndMeansNoKnownPosition() throws IOException {
    store.saveIfChanged("a", null, "text");
    Snapshot found = store.pending().get(0);
    assertEquals(0, found.caret());
    assertEquals(-1, found.tabIndex());
  }

  @Test
  void anOutOfRangeCursorIsClampedIntoTheText() throws IOException {
    store.saveIfChanged("big", null, "abc", 99, 0);
    store.saveIfChanged("neg", null, "abc", -5, 0);
    store.saveIfChanged("end", null, "abc", 3, 0);
    store.saveIfChanged("empty", null, "", 7, 0);
    java.util.Map<String, Integer> carets = new java.util.HashMap<>();
    for (Snapshot s : store.pending()) carets.put(s.id(), s.caret());
    assertEquals(3, carets.get("big"));
    assertEquals(0, carets.get("neg"));
    assertEquals(3, carets.get("end"), "the very end of the text is a valid cursor position");
    assertEquals(0, carets.get("empty"));
  }

  @Test
  void theCursorNeverLandsInsideASurrogatePair() throws IOException {
    String text = "a\uD83D\uDE00b"; // a, one emoji (two chars), b
    store.saveIfChanged("a", null, text, 2, 0); // offset 2 is between the emoji's two halves
    assertEquals(1, store.pending().get(0).caret());
  }

  @Test
  void aDamagedCursorOrTabLineNeverCostsTheText() throws IOException {
    Files.createDirectories(store.directory());
    Files.writeString(
        store.directory().resolve("badcaret.recovery"),
        "javelin-ide-recovery 1\noriginal=\nsaved=1\ncaret=oops\ntab=zzz\nbytes=4\n\nwork");
    Files.writeString(
        store.directory().resolve("hugecaret.recovery"),
        "javelin-ide-recovery 1\noriginal=\nsaved=2\ncaret=99999999999\ntab=-9\nbytes=4\n\nwork");
    List<Snapshot> found = store.pending();
    assertEquals(2, found.size());
    for (Snapshot s : found) {
      assertEquals("work", s.text());
      assertEquals(0, s.caret());
      assertEquals(-1, s.tabIndex());
    }
  }

  @Test
  void snapshotsFromBeforeCursorTrackingStillLoad() throws IOException {
    Files.createDirectories(store.directory());
    Files.writeString(
        store.directory().resolve("old.recovery"),
        "javelin-ide-recovery 1\noriginal=\nsaved=5\nbytes=4\n\nwork");
    Snapshot found = store.pending().get(0);
    assertEquals("work", found.text());
    assertEquals(0, found.caret());
    assertEquals(-1, found.tabIndex());
  }

  @Test
  void aSnapshotWithAnUnknownHeaderLineIsSkippedNotDeleted() throws IOException {
    Files.createDirectories(store.directory());
    Path f = store.directory().resolve("odd.recovery");
    Files.writeString(f, "javelin-ide-recovery 1\noriginal=\nsaved=5\nmystery=1\nbytes=4\n\nwork");
    Files.writeString(
        store.directory().resolve("swapped.recovery"),
        "javelin-ide-recovery 1\noriginal=\nsaved=5\ntab=1\ncaret=2\nbytes=4\n\nwork");
    assertTrue(store.pending().isEmpty());
    assertTrue(Files.exists(f));
  }

  @Test
  void restoreOrderFollowsTheOldTabOrderNotTheWriteOrder() throws Exception {
    store.saveIfChanged("third", null, "3", 0, 2);
    Thread.sleep(15);
    store.saveIfChanged("first", null, "1", 0, 0);
    Thread.sleep(15);
    store.saveIfChanged("second", null, "2", 0, 1);
    assertEquals(
        List.of("third", "first", "second"),
        store.pending().stream().map(Snapshot::id).toList(),
        "pending() stays oldest first");
    assertEquals(
        List.of("first", "second", "third"),
        store.pending().stream().sorted(RecoveryStore.TAB_ORDER).map(Snapshot::id).toList());
  }

  @Test
  void snapshotsWithoutATabPositionRestoreLastAndOldestFirst() throws Exception {
    store.saveIfChanged("unknown-old", null, "x");
    Thread.sleep(15);
    store.saveIfChanged("unknown-new", null, "y");
    Thread.sleep(15);
    store.saveIfChanged("tab-5", null, "z", 0, 5);
    assertEquals(
        List.of("tab-5", "unknown-old", "unknown-new"),
        store.pending().stream().sorted(RecoveryStore.TAB_ORDER).map(Snapshot::id).toList());
  }

  @Test
  void theCompatibilityConstructorHasNoPosition() {
    Snapshot s = new Snapshot("id", null, Instant.EPOCH, "abc");
    assertEquals(0, s.caret());
    assertEquals(-1, s.tabIndex());
    assertEquals(1, new Snapshot("id", null, Instant.EPOCH, "abc", 1, -7).caret());
    assertEquals(-1, new Snapshot("id", null, Instant.EPOCH, "abc", 1, -7).tabIndex());
  }

  private record FileTimeProbe(Path file, FileTime before) {
    FileTimeProbe(Path file) throws IOException {
      this(file, Files.getLastModifiedTime(file));
    }

    boolean unchanged() throws IOException {
      return Files.getLastModifiedTime(file).equals(before);
    }
  }
}
