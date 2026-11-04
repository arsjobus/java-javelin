package com.javelin.icon;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IconExporterTest {
  @TempDir Path dir;

  private static List<BufferedImage> icons(int... sizes) {
    List<BufferedImage> list = new ArrayList<>();
    for (int size : sizes) list.add(AppIcon.defaultIcon(size));
    return list;
  }

  private static boolean isPng(byte[] data, int offset) {
    return (data[offset] & 0xFF) == 0x89
        && data[offset + 1] == 'P'
        && data[offset + 2] == 'N'
        && data[offset + 3] == 'G';
  }

  // ---- ICO (Windows) ----

  @Test
  void icoHasTheDirectoryHeaderAndOneEntryPerImage() throws IOException {
    byte[] ico = IconExporter.ico(icons(16, 32, 48));
    ByteBuffer in = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0, in.getShort(0)); // reserved
    assertEquals(1, in.getShort(2)); // type: icon
    assertEquals(3, in.getShort(4)); // count
    assertEquals(16, in.get(6) & 0xFF);
    assertEquals(32, in.get(6 + 16) & 0xFF);
    assertEquals(48, in.get(6 + 32) & 0xFF);
    assertEquals(1, in.getShort(6 + 4)); // planes
    assertEquals(32, in.getShort(6 + 6)); // bits per pixel
  }

  @Test
  void icoEntriesPointAtTheEmbeddedPngsAndTheFileHasNoSlack() throws IOException {
    byte[] ico = IconExporter.ico(icons(16, 64));
    ByteBuffer in = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
    int end = 0;
    for (int i = 0; i < 2; i++) {
      int size = in.getInt(6 + 16 * i + 8), offset = in.getInt(6 + 16 * i + 12);
      assertTrue(isPng(ico, offset), "entry " + i + " starts with a PNG signature");
      BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(ico, offset, size));
      assertEquals(i == 0 ? 16 : 64, image.getWidth());
      end = offset + size;
    }
    assertEquals(ico.length, end);
  }

  @Test
  void icoWritesA256PixelImageAsZero() throws IOException {
    byte[] ico = IconExporter.ico(icons(256));
    assertEquals(0, ico[6]);
    assertEquals(0, ico[7]);
  }

  @Test
  void icoRejectsImagesBiggerThan256() {
    assertThrows(IllegalArgumentException.class, () -> IconExporter.ico(icons(512)));
    BufferedImage tall = new BufferedImage(16, 257, BufferedImage.TYPE_INT_ARGB);
    assertThrows(IllegalArgumentException.class, () -> IconExporter.ico(List.of(tall)));
  }

  // ---- ICNS (macOS) ----

  @Test
  void icnsHasTheMagicTheTotalLengthAndTheRightElementTypes() throws IOException {
    byte[] icns = IconExporter.icns(icons(16, 128, 512));
    ByteBuffer in = ByteBuffer.wrap(icns).order(ByteOrder.BIG_ENDIAN);
    assertEquals("icns", new String(icns, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    assertEquals(icns.length, in.getInt(4));
    int position = 8;
    String[] expected = {"icp4", "ic07", "ic09"};
    for (String type : expected) {
      assertEquals(type, new String(icns, position, 4, java.nio.charset.StandardCharsets.US_ASCII));
      int length = in.getInt(position + 4);
      assertTrue(isPng(icns, position + 8));
      position += length;
    }
    assertEquals(icns.length, position);
  }

  @Test
  void icnsKnowsEverySupportedSize() throws IOException {
    byte[] icns = IconExporter.icns(icons(16, 32, 64, 128, 256, 512, 1024));
    String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
    ByteBuffer in = ByteBuffer.wrap(icns);
    int position = 8;
    for (String type : types) {
      assertEquals(type, new String(icns, position, 4, java.nio.charset.StandardCharsets.US_ASCII));
      position += in.getInt(position + 4);
    }
  }

  @Test
  void icnsRejectsSizesItCannotDescribe() {
    assertThrows(IllegalArgumentException.class, () -> IconExporter.icns(icons(48)));
    BufferedImage wide = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
    assertThrows(IllegalArgumentException.class, () -> IconExporter.icns(List.of(wide)));
  }

  // ---- export ----

  @Test
  void exportWritesAWindowsAMacAndALinuxIcon() throws IOException {
    Path target = dir.resolve("nested/icons");
    IconExporter.export(target);
    byte[] ico = Files.readAllBytes(target.resolve("icon.ico")),
        icns = Files.readAllBytes(target.resolve("icon.icns"));
    assertEquals(6, ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN).getShort(4));
    assertEquals("icns", new String(icns, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    BufferedImage png = ImageIO.read(target.resolve("icon.png").toFile());
    assertEquals(512, png.getWidth());
    assertEquals(512, png.getHeight());
  }

  @Test
  void mainExportsIntoTheGivenDirectory() throws IOException {
    IconExporter.main(new String[] {dir.resolve("out").toString()});
    assertTrue(Files.isRegularFile(dir.resolve("out/icon.ico")));
    assertTrue(Files.isRegularFile(dir.resolve("out/icon.icns")));
    assertTrue(Files.isRegularFile(dir.resolve("out/icon.png")));
  }

  @Test
  void theDefaultExportFolderIsPackagingIcons() {
    assertEquals("packaging/icons", IconExporter.DEFAULT_DIRECTORY);
  }
}
