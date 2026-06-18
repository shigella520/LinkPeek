package io.github.shigella520.linkpeek.core.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleCardRendererTest {
    private static final int AVAILABLE_WIDTH = 1016;
    private static final int AVAILABLE_HEIGHT = 486;

    @TempDir
    Path tempDir;

    @Test
    void cjkTitleCanBreakAfterCharactersInsteadOfStoppingAtEarlyPunctuation() {
        String earlyClause = "考上985后才明白，";
        String title = earlyClause + "专业选择和地理环境远比较名重要，普通大学的优质专业与一线城市资源更能决定未来";

        BufferedImage image = new BufferedImage(TitleCardRenderer.WIDTH, TitleCardRenderer.HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            TitleCardRenderer.TextLayout layout = TitleCardRenderer.fitTitleLayout(
                    graphics,
                    title,
                    AVAILABLE_WIDTH,
                    AVAILABLE_HEIGHT
            );

            FontMetrics metrics = graphics.getFontMetrics(layout.font());
            assertTrue(layout.lines().size() <= 3);
            assertNotEquals(earlyClause, layout.lines().get(0));
            for (String line : layout.lines()) {
                assertTrue(metrics.stringWidth(line) <= AVAILABLE_WIDTH);
            }
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void watermarkChangesRenderedCard() throws Exception {
        String title = "标题卡片水印测试";
        String seed = "https://example.com/topic/1";
        Path plain = tempDir.resolve("plain.jpg");
        Path watermarked = tempDir.resolve("watermarked.jpg");

        TitleCardRenderer.render(title, "Fallback", seed, "TEST", plain);
        TitleCardRenderer.render(
                title,
                "Fallback",
                seed,
                "TEST",
                TitleCardRenderer.Watermark.resource(TitleCardRendererTest.class, "test-watermark.svg"),
                watermarked
        );

        assertNotEquals(
                Arrays.hashCode(Files.readAllBytes(plain)),
                Arrays.hashCode(Files.readAllBytes(watermarked))
        );
    }

    @Test
    void watermarkOffsetChangesRenderedCard() throws Exception {
        String title = "标题卡片水印偏移测试";
        String seed = "https://example.com/topic/1";
        TitleCardRenderer.Watermark watermark =
                TitleCardRenderer.Watermark.resource(TitleCardRendererTest.class, "test-watermark.svg");
        Path first = tempDir.resolve("first-offset.jpg");
        Path second = tempDir.resolve("second-offset.jpg");

        try {
            TitleCardRenderer.setWatermarkOffsetGeneratorForTesting((maxXExclusive, maxYExclusive) ->
                    new TitleCardRenderer.WatermarkOffset(0, 0));
            TitleCardRenderer.render(title, "Fallback", seed, "TEST", watermark, first);

            TitleCardRenderer.setWatermarkOffsetGeneratorForTesting((maxXExclusive, maxYExclusive) ->
                    new TitleCardRenderer.WatermarkOffset(maxXExclusive / 2, maxYExclusive / 2));
            TitleCardRenderer.render(title, "Fallback", seed, "TEST", watermark, second);
        } finally {
            TitleCardRenderer.resetWatermarkOffsetGeneratorForTesting();
        }

        assertNotEquals(
                Arrays.hashCode(Files.readAllBytes(first)),
                Arrays.hashCode(Files.readAllBytes(second))
        );
    }

    @Test
    void watermarkColorIsRemovedBeforeRendering() {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new java.awt.Color(255, 180, 0, 200).getRGB());

        BufferedImage grayscale = TitleCardRenderer.removeWatermarkColor(source);
        java.awt.Color color = new java.awt.Color(grayscale.getRGB(0, 0), true);

        assertEquals(color.getRed(), color.getGreen());
        assertEquals(color.getGreen(), color.getBlue());
        assertEquals(200, color.getAlpha());
    }
}
