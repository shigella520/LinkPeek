package io.github.shigella520.linkpeek.core.media;

import io.github.shigella520.linkpeek.core.util.CardTextSanitizer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

public final class ThumbnailBadgeRenderer {
    private static final float JPEG_QUALITY = 0.92f;
    private static final int REFERENCE_WIDTH = 1200;
    private static final int REFERENCE_HEIGHT = 630;
    private static final int REFERENCE_BADGE_X = 92;
    private static final int REFERENCE_BADGE_Y = 48;
    private static final int REFERENCE_BADGE_FONT_SIZE = 26;
    private static final int REFERENCE_SHADOW_OFFSET = 2;
    private static final int MIN_BADGE_FONT_SIZE = 14;
    private static final int TITLE_FONT_STYLE = Font.BOLD;
    private static final List<String> FONT_FAMILIES = List.of(
            "Heiti SC",
            "STHeiti",
            "PingFang SC",
            "Hiragino Sans GB",
            "Microsoft YaHei",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            Font.SANS_SERIF
    );

    private ThumbnailBadgeRenderer() {
    }

    public static Path render(byte[] sourceBytes, String badgeLabel, Path targetPath) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if (source == null) {
            throw new IOException("Unsupported thumbnail image format.");
        }

        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            applyQualityHints(graphics);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            graphics.drawImage(source, 0, 0, null);
            paintBadge(graphics, source.getWidth(), source.getHeight(), badgeLabel);
        } finally {
            graphics.dispose();
        }
        writeJpeg(output, targetPath);
        return targetPath;
    }

    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static void paintBadge(Graphics2D graphics, int imageWidth, int imageHeight, String badgeLabel) {
        String label = CardTextSanitizer.sanitize(badgeLabel);
        if (label.isBlank()) {
            return;
        }

        double scale = badgeScale(imageWidth, imageHeight);
        int fontSize = Math.max(MIN_BADGE_FONT_SIZE, (int) Math.round(REFERENCE_BADGE_FONT_SIZE * scale));
        Font badgeFont = selectFont(label, fontSize).deriveFont(Font.BOLD, fontSize);
        graphics.setFont(badgeFont);
        FontMetrics metrics = graphics.getFontMetrics(badgeFont);
        int badgeX = Math.max(12, (int) Math.round(REFERENCE_BADGE_X * scale));
        int badgeY = Math.max(10, (int) Math.round(REFERENCE_BADGE_Y * scale));
        int baselineY = badgeY + metrics.getAscent();
        int shadowOffset = Math.max(1, (int) Math.round(REFERENCE_SHADOW_OFFSET * scale));

        graphics.setColor(new Color(0, 0, 0, 52));
        graphics.drawString(label, badgeX + shadowOffset, baselineY + shadowOffset);
        graphics.setColor(new Color(255, 255, 255, 236));
        graphics.drawString(label, badgeX, baselineY);
    }

    private static double badgeScale(int imageWidth, int imageHeight) {
        return Math.min((double) imageWidth / REFERENCE_WIDTH, (double) imageHeight / REFERENCE_HEIGHT);
    }

    private static Font selectFont(String text, int fontSize) {
        for (String family : FONT_FAMILIES) {
            Font font = new Font(family, TITLE_FONT_STYLE, fontSize);
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, TITLE_FONT_STYLE, fontSize);
    }

    private static void writeJpeg(BufferedImage image, Path targetPath) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer is available.");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        if (writeParam.canWriteCompressed()) {
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(JPEG_QUALITY);
        }

        try (
                OutputStream fileOutputStream = Files.newOutputStream(targetPath);
                ImageOutputStream outputStream = ImageIO.createImageOutputStream(fileOutputStream)
        ) {
            if (outputStream == null) {
                throw new IOException("No JPEG image output stream is available.");
            }
            writer.setOutput(outputStream);
            writer.write(null, new IIOImage(image, null, null), writeParam);
        } finally {
            writer.dispose();
        }
    }
}
