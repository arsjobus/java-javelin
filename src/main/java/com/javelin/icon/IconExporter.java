package com.javelin.icon;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the built-in icon in the file formats the platform packagers want, so a native build (for
 * example {@code jpackage --icon ...}) can use it: {@code icon.ico} for Windows, {@code icon.icns}
 * for macOS and {@code icon.png} for Linux. Both containers hold PNG-compressed images, which every
 * supported OS version reads.
 *
 * <p>Run after {@code mvn compile}: {@code java -cp target/classes com.javelin.icon.IconExporter
 * [directory]} (the default directory is {@code packaging/icons}).
 */
public final class IconExporter {
  private IconExporter() {}

  public static final String DEFAULT_DIRECTORY = "packaging/icons";

  /** Largest image an ICO entry can describe. */
  static final int ICO_MAX = 256;

  /** ICNS element types for PNG data, by pixel size. */
  private static final int[][] ICNS_TYPES = {
    {16, 0x69637034}, // icp4
    {32, 0x69637035}, // icp5
    {64, 0x69637036}, // icp6
    {128, 0x69633037}, // ic07
    {256, 0x69633038}, // ic08
    {512, 0x69633039}, // ic09
    {1024, 0x69633130}, // ic10
  };

  /** A Windows icon holding {@code images} (each at most 256 pixels square). */
  public static byte[] ico(List<BufferedImage> images) throws IOException {
    List<byte[]> data = new ArrayList<>();
    int offset = 6 + 16 * images.size(), total = offset;
    for (BufferedImage image : images) {
      if (image.getWidth() > ICO_MAX || image.getHeight() > ICO_MAX)
        throw new IllegalArgumentException(
            "ICO images can be at most "
                + ICO_MAX
                + " pixels, got "
                + image.getWidth()
                + "x"
                + image.getHeight());
      byte[] png = AppIcon.png(image);
      data.add(png);
      total += png.length;
    }
    ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    out.putShort((short) 0)
        .putShort((short) 1)
        .putShort((short) images.size()); // reserved, type = icon, count
    for (int i = 0; i < images.size(); i++) {
      BufferedImage image = images.get(i);
      out.put((byte) (image.getWidth() == ICO_MAX ? 0 : image.getWidth())); // 0 means 256
      out.put((byte) (image.getHeight() == ICO_MAX ? 0 : image.getHeight()));
      out.put((byte) 0).put((byte) 0); // palette size, reserved
      out.putShort((short) 1).putShort((short) 32); // planes, bits per pixel
      out.putInt(data.get(i).length).putInt(offset);
      offset += data.get(i).length;
    }
    for (byte[] png : data) out.put(png);
    return out.array();
  }

  /**
   * A macOS icon holding {@code images}; each must be 16, 32, 64, 128, 256, 512 or 1024 pixels
   * square.
   */
  public static byte[] icns(List<BufferedImage> images) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (BufferedImage image : images) {
      int type = icnsType(image);
      byte[] png = AppIcon.png(image);
      ByteBuffer element = ByteBuffer.allocate(8 + png.length).order(ByteOrder.BIG_ENDIAN);
      element.putInt(type).putInt(8 + png.length).put(png);
      body.write(element.array());
    }
    ByteBuffer out = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.BIG_ENDIAN);
    out.putInt(0x69636E73).putInt(8 + body.size()).put(body.toByteArray()); // 'icns', total length
    return out.array();
  }

  private static int icnsType(BufferedImage image) {
    if (image.getWidth() == image.getHeight())
      for (int[] entry : ICNS_TYPES) if (entry[0] == image.getWidth()) return entry[1];
    throw new IllegalArgumentException(
        "ICNS needs a square image of 16, 32, 64, 128, 256, 512 or 1024 pixels, got "
            + image.getWidth()
            + "x"
            + image.getHeight());
  }

  /**
   * Writes {@code icon.ico}, {@code icon.icns} and {@code icon.png} (512 pixels) into {@code
   * directory}.
   */
  public static void export(Path directory) throws IOException {
    Files.createDirectories(directory);
    List<BufferedImage> windows = new ArrayList<>(), mac = new ArrayList<>();
    for (int size : new int[] {16, 32, 48, 64, 128, 256}) windows.add(AppIcon.defaultIcon(size));
    for (int[] entry : ICNS_TYPES) mac.add(AppIcon.defaultIcon(entry[0]));
    Files.write(directory.resolve("icon.ico"), ico(windows));
    Files.write(directory.resolve("icon.icns"), icns(mac));
    Files.write(directory.resolve("icon.png"), AppIcon.png(AppIcon.defaultIcon(512)));
  }

  public static void main(String[] args) throws IOException {
    export(Path.of(args.length > 0 ? args[0] : DEFAULT_DIRECTORY));
  }
}
