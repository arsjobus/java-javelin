package com.javelin_ide.icon;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The application icon. The default is drawn in code (a purple rounded square with a {@code </>} mark), so it is compiled
 * in, needs no binary asset, looks the same on Windows, macOS and Linux, and is crisp at every size. The user can
 * replace it with their own image through the {@code app.icon} setting.
 *
 * <p>Nothing here needs a display: it works on {@link BufferedImage}s, so it runs (and is tested) headless.
 */
public final class AppIcon {
    private AppIcon() {}

    /** The user-file setting that points at a replacement image. */
    public static final String SETTING = "app.icon";

    /** Sizes handed to the window system, smallest first; the platform picks the one that fits best. */
    public static final int[] SIZES = {16, 32, 48, 64, 128, 256, 512};

    /** The icons to use, plus a message for the status bar when the configured image could not be used. */
    public record Result(List<BufferedImage> images, String warning) {}

    /** Reads an image file; replaceable so the unreadable-file path can be tested. */
    interface ImageReader { BufferedImage read(Path file) throws IOException; }

    /** The built-in icon at {@code size} x {@code size} pixels. */
    public static BufferedImage defaultIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double s = size;
            // Leave a Dock-style safe margin so the visible tile doesn't overpower neighboring app icons.
            g.translate(s * 0.08, s * 0.08);
            g.scale(0.84, 0.84);
            RoundRectangle2D tile = new RoundRectangle2D.Double(s * 0.025, s * 0.025, s * 0.95, s * 0.95, s * 0.24, s * 0.24);
            g.setPaint(new GradientPaint(0, 0, new Color(0x9C62E8), size, size, new Color(0x512DA8)));
            g.fill(tile);
            // A restrained violet glow and top sheen give the dark tile the soft depth of macOS Dock icons.
            g.setComposite(AlphaComposite.SrcAtop);
            g.setPaint(new RadialGradientPaint((float) (s * 0.52), (float) (s * 0.27), (float) (s * 0.78),
                new float[] {0f, 0.52f, 1f}, new Color[] {
                    new Color(128, 98, 196, 76), new Color(48, 35, 74, 30), new Color(28, 23, 36, 0)
                }));
            g.fill(tile);
            g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 22), 0, (float) (s * 0.55), new Color(255, 255, 255, 0)));
            g.fill(tile);
            g.setComposite(AlphaComposite.SrcOver);
            g.setStroke(new BasicStroke((float) (s * 0.075), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D left = new Path2D.Double();   // <
            left.moveTo(s * 0.37, s * 0.30); left.lineTo(s * 0.20, s * 0.50); left.lineTo(s * 0.37, s * 0.70);
            Path2D right = new Path2D.Double();  // >
            right.moveTo(s * 0.63, s * 0.30); right.lineTo(s * 0.80, s * 0.50); right.lineTo(s * 0.63, s * 0.70);
            g.setColor(new Color(20, 12, 30, 110));
            g.translate(0, s * 0.009);
            g.draw(left);
            g.draw(right);
            g.draw(new java.awt.geom.Line2D.Double(s * 0.55, s * 0.26, s * 0.45, s * 0.74));
            g.translate(0, -s * 0.009);
            g.setColor(new Color(0xFFF9FF));
            g.draw(left);
            g.draw(right);
            g.draw(new java.awt.geom.Line2D.Double(s * 0.55, s * 0.26, s * 0.45, s * 0.74));   // /
        } finally {
            g.dispose();
        }
        return image;
    }

    /** {@code source} fitted inside a transparent {@code size} x {@code size} square, keeping its proportions. */
    public static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double factor = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The built-in icon at every size in {@link #SIZES}. */
    public static List<BufferedImage> defaultIcons() {
        List<BufferedImage> icons = new ArrayList<>();
        for (int size : SIZES) icons.add(defaultIcon(size));
        return icons;
    }

    /**
     * The icons for the {@code app.icon} setting: blank means the built-in icon. A path to a PNG, JPEG or GIF is
     * scaled to every size; {@code ~} stands for the home directory. Anything that cannot be used (missing file,
     * not an image, unreadable) falls back to the built-in icon with a warning, never an exception.
     */
    public static Result resolve(String configured) {
        return resolve(configured, file -> ImageIO.read(file.toFile()));
    }

    static Result resolve(String configured, ImageReader reader) {
        if (configured == null || configured.isBlank()) return new Result(defaultIcons(), null);
        String text = configured.strip();
        Path file;
        try {
            file = Path.of(expandHome(text));
        } catch (InvalidPathException ex) {
            return fallback(SETTING + ": '" + text + "' is not a valid path; using the built-in icon");
        }
        if (!Files.isRegularFile(file)) return fallback(SETTING + ": no file at " + file + "; using the built-in icon");
        BufferedImage image;
        try {
            image = reader.read(file);
        } catch (IOException ex) {
            return fallback(SETTING + ": could not read " + file + " (" + ex.getMessage() + "); using the built-in icon");
        }
        if (image == null) return fallback(SETTING + ": " + file + " is not a PNG, JPEG or GIF image; using the built-in icon");
        List<BufferedImage> icons = new ArrayList<>();
        for (int size : SIZES) icons.add(scale(image, size));
        return new Result(icons, null);
    }

    /** The image as PNG bytes. */
    public static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static Result fallback(String warning) { return new Result(defaultIcons(), warning); }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\"))
            return System.getProperty("user.home", ".") + path.substring(1);
        return path;
    }
}
