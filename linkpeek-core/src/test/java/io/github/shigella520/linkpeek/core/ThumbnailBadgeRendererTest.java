package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.media.ThumbnailBadgeRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailBadgeRendererTest {
    @Test
    void rendersBadgeOnImageAsJpeg() throws IOException {
        Path target = Files.createTempDirectory("linkpeek-thumbnail-badge").resolve("thumb.jpg");

        ThumbnailBadgeRenderer.render(testImageBytes(160, 90), "Bilibili", target);

        BufferedImage image = ImageIO.read(target.toFile());
        assertNotNull(image);
        assertEquals("JPEG", imageFormatName(target));
        assertEquals(160, image.getWidth());
        assertEquals(90, image.getHeight());
    }

    @Test
    void skipsBlankBadgeAfterSanitization() throws IOException {
        Path target = Files.createTempDirectory("linkpeek-thumbnail-badge").resolve("blank.jpg");

        ThumbnailBadgeRenderer.render(testImageBytes(120, 80), "😄", target);

        BufferedImage image = ImageIO.read(target.toFile());
        assertNotNull(image);
        assertEquals("JPEG", imageFormatName(target));
        assertEquals(120, image.getWidth());
        assertEquals(80, image.getHeight());
    }

    @Test
    void rejectsUnsupportedImageBytes() {
        Path target = Path.of(System.getProperty("java.io.tmpdir"), "linkpeek-invalid-thumb.jpg");

        assertThrows(
                IOException.class,
                () -> ThumbnailBadgeRenderer.render("not-an-image".getBytes(StandardCharsets.UTF_8), "Bilibili", target)
        );
    }

    private static byte[] testImageBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(56, 148, 214));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    private static String imageFormatName(Path path) throws IOException {
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(path.toFile())) {
            assertNotNull(inputStream);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
            assertTrue(readers.hasNext());
            ImageReader reader = readers.next();
            try {
                return reader.getFormatName();
            } finally {
                reader.dispose();
            }
        }
    }
}
