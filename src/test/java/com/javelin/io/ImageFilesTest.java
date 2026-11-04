package com.javelin.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageFilesTest {
  @TempDir Path dir;

  @Test
  void recognisesPictureExtensionsInAnyCase() {
    for (String name :
        new String[] {
          "a.png", "a.PNG", "photo.jpg", "photo.JPEG", "anim.gif", "scan.Bmp", "my.file.name.png"
        }) assertTrue(ImageFiles.isImage(name), name);
    assertTrue(ImageFiles.isImage(Path.of("some", "dir", "pic.png")));
  }

  @Test
  void everythingElseStaysText() {
    for (String name :
        new String[] {
          "Main.java",
          "icon.svg",
          "notes.txt",
          "png",
          ".png",
          "pic.",
          "pic.png.bak",
          "archive.zip",
          "",
          "   "
        }) assertFalse(ImageFiles.isImage(name), name);
    assertFalse(ImageFiles.isImage((String) null));
    assertFalse(ImageFiles.isImage((Path) null));
  }

  @Test
  void fitNeverEnlargesAndNeverCollapses() {
    assertEquals(1.0, ImageFiles.fitScale(100, 50, 800, 600)); // small picture: actual size
    assertEquals(0.5, ImageFiles.fitScale(1600, 400, 800, 600)); // limited by width
    assertEquals(0.25, ImageFiles.fitScale(400, 2400, 800, 600)); // limited by height
    assertEquals(1.0, ImageFiles.fitScale(1600, 400, 0, 0)); // window not laid out yet
    assertEquals(1.0, ImageFiles.fitScale(0, 0, 800, 600));
    assertEquals(1.0, ImageFiles.fitScale(Double.NaN, 10, 800, 600));
  }

  @Test
  void zoomStaysInUsefulRange() {
    assertEquals(0.05, ImageFiles.clampZoom(0.0001));
    assertEquals(16, ImageFiles.clampZoom(1000));
    assertEquals(2, ImageFiles.clampZoom(2));
  }

  @Test
  void describesDimensionsAndSize() {
    assertEquals("640 × 480 px, 12.5 KB", ImageFiles.describe(640, 480, 12800));
  }

  @Test
  void loadableChecksExistenceAndSize() throws IOException {
    Path ok = Files.write(dir.resolve("a.png"), new byte[10]);
    assertDoesNotThrow(() -> ImageFiles.checkLoadable(ok));
    IOException missing =
        assertThrows(IOException.class, () -> ImageFiles.checkLoadable(dir.resolve("nope.png")));
    assertTrue(missing.getMessage().contains("nope.png"));
    assertThrows(
        IOException.class, () -> ImageFiles.checkLoadable(dir)); // a folder named like a picture
  }
}
