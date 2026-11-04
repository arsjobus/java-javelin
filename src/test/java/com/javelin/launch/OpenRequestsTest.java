package com.javelin.launch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRequestsTest {
  @TempDir Path work;
  Path folder;
  OpenRequests requests;
  Path projectA, projectB;

  @BeforeEach
  void setUp() throws IOException {
    folder = work.resolve(OpenRequests.DIRECTORY_NAME);
    requests = new OpenRequests(folder);
    projectA = Files.createDirectory(work.resolve("A"));
    projectB = Files.createDirectory(work.resolve("B"));
  }

  private List<String> names() throws IOException {
    try (Stream<Path> s = Files.list(folder)) {
      return s.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  @Test
  void exposesItsDirectory() {
    assertEquals(folder, requests.directory());
  }

  @Test
  void nothingIsPendingBeforeAnythingWasPosted() {
    assertTrue(requests.take().isEmpty(), "the folder does not even exist yet");
  }

  @Test
  void aPostedRequestIsTakenOnceAndConsumed() throws IOException {
    Path file = requests.post(projectA);
    assertTrue(file.getFileName().toString().endsWith(OpenRequests.SUFFIX));
    assertEquals(
        projectA.toAbsolutePath().normalize() + "\n",
        Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(projectA, requests.take().orElseThrow());
    assertEquals(List.of(), names(), "consumed");
    assertTrue(requests.take().isEmpty());
  }

  @Test
  void theNewestOfSeveralRequestsWinsAndAllAreConsumed() throws IOException {
    requests.post(projectA);
    requests.post(projectB);
    assertEquals(projectB, requests.take().orElseThrow());
    assertEquals(List.of(), names());
  }

  @Test
  void aRequestStillBeingWrittenIsIgnored() throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve(".123-1.tmp"), projectA + "\n");
    Files.writeString(folder.resolve("notes.txt"), projectA + "\n");
    assertTrue(requests.take().isEmpty());
    assertEquals(List.of(".123-1.tmp", "notes.txt"), names(), "left alone");
  }

  @Test
  void invalidRequestsAreDiscardedAndDoNotHideAValidOne() throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("1.request"), projectA + "\n");
    Files.writeString(folder.resolve("2.request"), "relative/path\n");
    Files.writeString(folder.resolve("3.request"), work.resolve("missing") + "\n");
    Files.writeString(folder.resolve("4.request"), "\n");
    Files.write(folder.resolve("5.request"), new byte[0]);
    Files.writeString(folder.resolve("6.request"), "bad\0path\n");
    Files.write(folder.resolve("7.request"), new byte[] {(byte) 0xC3, (byte) 0x28}); // not UTF-8
    Files.writeString(folder.resolve("8.request"), work.resolve("A.txt") + "\n");
    assertEquals(projectA, requests.take().orElseThrow());
    assertEquals(List.of(), names());
  }

  @Test
  void onlyTheFirstLineCounts() throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("1.request"), "  " + projectB + "  \n" + projectA + "\n");
    assertEquals(projectB, requests.take().orElseThrow());
  }

  @Test
  void aRequestThatCannotBeReadOrRemovedDoesNotBreakTaking() throws IOException {
    Files.createDirectories(folder.resolve("stuck.request")); // a directory, not a file
    Files.writeString(
        folder.resolve("stuck.request").resolve("inner"), "x"); // so it cannot be deleted either
    Files.writeString(folder.resolve("z.request"), projectA + "\n");
    assertEquals(projectA, requests.take().orElseThrow());
    assertTrue(Files.exists(folder.resolve("stuck.request")));
  }
}
