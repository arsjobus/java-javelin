package com.javelin_ide.icon;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin_ide.icon.AppIcon.Result;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppIconTest {
    @TempDir Path dir;

    private static int alpha(BufferedImage image, int x, int y) { return image.getRGB(x, y) >>> 24; }

    private Path writePng(String name, int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setRGB(x, y, argb);
        Path file = dir.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static void assertBuiltIn(Result result) {
        assertEquals(AppIcon.SIZES.length, result.images().size());
        for (int i = 0; i < AppIcon.SIZES.length; i++) assertEquals(AppIcon.SIZES[i], result.images().get(i).getWidth());
    }

    // ---- the built-in icon ----

    @Test void builtInIconHasTheRequestedSizeAndIsNotJustAFolder() {
        for (int size : AppIcon.SIZES) {
            BufferedImage icon = AppIcon.defaultIcon(size);
            assertEquals(size, icon.getWidth());
            assertEquals(size, icon.getHeight());
            assertTrue(alpha(icon, size / 2, size / 2) > 0 || alpha(icon, size / 2, size / 3) > 0, "the middle of the icon is painted");
        }
    }

    @Test void builtInIconHasTransparentRoundedCornersAndAnOpaqueBody() {
        BufferedImage icon = AppIcon.defaultIcon(256);
        assertEquals(0, alpha(icon, 0, 0));
        assertEquals(0, alpha(icon, 255, 255));
        assertEquals(255, alpha(icon, 128, 38));   // body, above the mark and inside the safe margin
    }

    @Test void builtInIconContainsALightMarkOnAPurpleBody() {
        BufferedImage icon = AppIcon.defaultIcon(256);
        int body = icon.getRGB(128, 38) & 0xFFFFFF, mark = icon.getRGB(64, 128) & 0xFFFFFF;   // tip of the '<' after scaling
        assertNotEquals(body, mark);
        assertTrue((mark >> 16 & 0xFF) > 0xC0, "the mark is light");
        assertTrue((body & 0xFF) > (body >> 16 & 0xFF), "the body is purple");
    }

    @Test void builtInIconsAreOnePerSizeSmallestFirst() {
        List<BufferedImage> icons = AppIcon.defaultIcons();
        assertEquals(AppIcon.SIZES.length, icons.size());
        for (int i = 1; i < icons.size(); i++) assertTrue(icons.get(i).getWidth() > icons.get(i - 1).getWidth());
    }

    // ---- scaling ----

    @Test void scaleFitsASquareImage() {
        BufferedImage scaled = AppIcon.scale(AppIcon.defaultIcon(100), 32);
        assertEquals(32, scaled.getWidth());
        assertEquals(32, scaled.getHeight());
    }

    @Test void scaleKeepsProportionsAndCentresAWideImage() throws IOException {
        BufferedImage wide = ImageIO.read(writePng("wide.png", 40, 10, 0xFFFF0000).toFile());
        BufferedImage scaled = AppIcon.scale(wide, 20);
        assertEquals(20, scaled.getWidth());
        assertEquals(255, alpha(scaled, 10, 10));   // centre is the image
        assertEquals(0, alpha(scaled, 10, 1));      // above it is padding
        assertEquals(0, alpha(scaled, 10, 18));     // below it is padding
    }

    @Test void scaleKeepsProportionsAndCentresATallImage() throws IOException {
        BufferedImage tall = ImageIO.read(writePng("tall.png", 10, 40, 0xFF00FF00).toFile());
        BufferedImage scaled = AppIcon.scale(tall, 20);
        assertEquals(255, alpha(scaled, 10, 10));
        assertEquals(0, alpha(scaled, 1, 10));
        assertEquals(0, alpha(scaled, 18, 10));
    }

    @Test void scaleNeverCollapsesAVeryThinImageToNothing() {
        BufferedImage thin = new BufferedImage(1000, 1, BufferedImage.TYPE_INT_ARGB);
        thin.setRGB(0, 0, 0xFF000000);
        BufferedImage scaled = AppIcon.scale(thin, 16);
        assertEquals(16, scaled.getWidth());
    }

    // ---- png ----

    @Test void pngBytesAreAReadablePngOfTheSameSize() throws IOException {
        byte[] bytes = AppIcon.png(AppIcon.defaultIcon(64));
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals('P', bytes[1]);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(bytes));
        assertEquals(64, back.getWidth());
        assertEquals(64, back.getHeight());
    }

    // ---- the app.icon setting ----

    @Test void blankSettingMeansTheBuiltInIconWithoutAWarning() {
        for (String blank : new String[] {null, "", "   "}) {
            Result result = AppIcon.resolve(blank);
            assertBuiltIn(result);
            assertNull(result.warning());
        }
    }

    @Test void aConfiguredPngIsScaledToEverySize() throws IOException {
        Path file = writePng("mine.png", 300, 300, 0xFF336699);
        Result result = AppIcon.resolve(file.toString());
        assertNull(result.warning());
        assertBuiltIn(result);
        assertEquals(0xFF336699, result.images().get(0).getRGB(8, 8));
    }

    @Test void surroundingSpacesInTheSettingAreIgnored() throws IOException {
        Path file = writePng("mine.png", 64, 64, 0xFF112233);
        Result result = AppIcon.resolve("  " + file + "  ");
        assertNull(result.warning());
        assertBuiltIn(result);
    }

    @Test void aMissingFileFallsBackToTheBuiltInIconWithAWarning() {
        Result result = AppIcon.resolve(dir.resolve("nope.png").toString());
        assertBuiltIn(result);
        assertNotNull(result.warning());
        assertTrue(result.warning().startsWith("app.icon:"));
        assertTrue(result.warning().contains("no file at"));
    }

    @Test void aFolderIsNotAnIcon() {
        Result result = AppIcon.resolve(dir.toString());
        assertBuiltIn(result);
        assertTrue(result.warning().contains("no file at"));
    }

    @Test void aFileThatIsNotAnImageFallsBackWithAWarning() throws IOException {
        Path file = dir.resolve("icon.png");
        Files.writeString(file, "this is not a picture");
        Result result = AppIcon.resolve(file.toString());
        assertBuiltIn(result);
        assertTrue(result.warning().contains("is not a PNG, JPEG or GIF image"));
    }

    @Test void anIcoOrIcnsFileCannotBeReadAndFallsBackWithAWarning() throws IOException {
        Path ico = dir.resolve("a.ico");
        Files.write(ico, IconExporter.ico(List.of(AppIcon.defaultIcon(32))));
        assertTrue(AppIcon.resolve(ico.toString()).warning().contains("is not a PNG, JPEG or GIF image"));
    }

    @Test void anUnreadableFileFallsBackWithAWarningInsteadOfThrowing() throws IOException {
        Path file = writePng("mine.png", 32, 32, 0xFF000000);
        Result result = AppIcon.resolve(file.toString(), f -> { throw new IOException("disk on fire"); });
        assertBuiltIn(result);
        assertTrue(result.warning().contains("could not read"));
        assertTrue(result.warning().contains("disk on fire"));
    }

    @Test void anInvalidPathFallsBackWithAWarning() {
        Result result = AppIcon.resolve("bad\0path.png");
        assertBuiltIn(result);
        assertTrue(result.warning().contains("is not a valid path"));
    }

    @Test void aLeadingTildeMeansTheHomeDirectory() throws IOException {
        writePng("home.png", 48, 48, 0xFF445566);
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", dir.toString());
            assertNull(AppIcon.resolve("~/home.png").warning());
            assertTrue(AppIcon.resolve("~/missing.png").warning().contains("no file at " + dir.resolve("missing.png")));
            assertTrue(AppIcon.resolve("~").warning().contains("no file at " + dir), "a bare ~ is the home folder, which is not an image");
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @Test void aTildeInTheMiddleOfAPathIsNotExpanded() {
        assertTrue(AppIcon.resolve("some~dir/icon.png").warning().contains("some~dir"));
    }
}
