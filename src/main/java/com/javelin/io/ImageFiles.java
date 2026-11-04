package com.javelin.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Deciding which files the editor shows as pictures instead of text, and the small amount of
 * arithmetic the image viewer needs. JavaFX-free, so the rules are tested without a display.
 *
 * <p>Only formats the JDK's JavaFX can decode are listed. SVG is deliberately absent: it is text,
 * so it still opens in the editor where it can be read and changed.
 */
public final class ImageFiles {
  /** Above this size an image is not loaded; decoding a huge bitmap can exhaust memory. */
  public static final long MAX_IMAGE_BYTES = 64L << 20;

  private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

  private ImageFiles() {}

  /**
   * True if the file name ends in an extension of a picture format the viewer can show
   * (case-insensitive).
   */
  public static boolean isImage(Path file) {
    Path name = file == null ? null : file.getFileName();
    return name != null && isImage(name.toString());
  }

  public static boolean isImage(String fileName) {
    if (fileName == null) return false;
    int dot = fileName.lastIndexOf('.');
    return dot > 0
        && dot < fileName.length() - 1
        && EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /** Refuses missing, non-regular and over-large files with a message a person can act on. */
  public static void checkLoadable(Path file) throws IOException {
    if (!Files.isRegularFile(file)) throw new IOException(file.getFileName() + " is not a file.");
    long size = Files.size(file);
    if (size > MAX_IMAGE_BYTES)
      throw new IOException(
          file.getFileName()
              + " is "
              + FileIO.describe(size)
              + ", which is more than the "
              + FileIO.describe(MAX_IMAGE_BYTES)
              + " the editor shows as an image.");
  }

  /**
   * The zoom that makes an image fit entirely inside a view: never above 1 (small images are not
   * blown up), and 1 when a size is not known yet, so a not-yet-laid-out window never collapses the
   * image to nothing.
   */
  public static double fitScale(
      double imageWidth, double imageHeight, double viewWidth, double viewHeight) {
    if (!(imageWidth > 0) || !(imageHeight > 0) || !(viewWidth > 0) || !(viewHeight > 0)) return 1;
    return Math.min(1, Math.min(viewWidth / imageWidth, viewHeight / imageHeight));
  }

  /** Keeps a zoom factor in a range where the picture is still usable. */
  public static double clampZoom(double zoom) {
    return Math.max(0.05, Math.min(16, zoom));
  }

  /** A line for the viewer's toolbar, e.g. {@code 640 × 480 px, 12.5 KB}. */
  public static String describe(int width, int height, long bytes) {
    return width + " × " + height + " px, " + FileIO.describe(bytes);
  }
}
