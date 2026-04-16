package io.github.shigella520.linkpeek.core.media;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TitleCardRenderer {
    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;

    private static final int HORIZONTAL_PADDING = 92;
    private static final int VERTICAL_PADDING = 72;
    private static final int MAX_TITLE_LINES = 3;
    private static final int MAX_FONT_SIZE = 86;
    private static final int MIN_FONT_SIZE = 42;
    private static final int FONT_STEP = 4;
    private static final int BADGE_FONT_SIZE = 26;
    private static final int BADGE_TOP_OFFSET = 48;
    private static final float JPEG_QUALITY = 0.92f;
    private static final int TITLE_FONT_STYLE = Font.PLAIN;
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
    private static final String ELLIPSIS = "…";

    private TitleCardRenderer() {
    }

    public static void render(String title, String fallbackTitle, String seed, Path targetPath) throws IOException {
        render(title, fallbackTitle, seed, null, targetPath);
    }

    public static void render(String title, String fallbackTitle, String seed, String badgeLabel, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            applyQualityHints(graphics);
            paintBackground(graphics, seed);
            paintBadge(graphics, badgeLabel);
            paintTitle(graphics, displayTitle(title, fallbackTitle));
        } finally {
            graphics.dispose();
        }
        writeJpeg(image, targetPath);
    }

    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static void paintBackground(Graphics2D graphics, String seed) {
        GradientSpec gradient = gradientFor(seed);
        graphics.setPaint(new GradientPaint(
                gradient.startX(),
                gradient.startY(),
                gradient.startColor(),
                gradient.endX(),
                gradient.endY(),
                gradient.endColor()
        ));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);

        graphics.setColor(withAlpha(gradient.startColor(), 56));
        graphics.fill(new Ellipse2D.Double(-140, -120, 520, 520));

        graphics.setColor(withAlpha(gradient.endColor(), 48));
        graphics.fill(new Ellipse2D.Double(WIDTH - 360, HEIGHT - 320, 460, 460));

        graphics.setColor(new Color(255, 255, 255, 24));
        graphics.fill(new Ellipse2D.Double(WIDTH * 0.48, -110, 340, 340));
    }

    private static void paintBadge(Graphics2D graphics, String badgeLabel) {
        String label = badgeLabel == null ? "" : badgeLabel.strip();
        if (label.isBlank()) {
            return;
        }

        Font badgeFont = selectFont(label, BADGE_FONT_SIZE).deriveFont(Font.BOLD, BADGE_FONT_SIZE);
        graphics.setFont(badgeFont);
        FontMetrics metrics = graphics.getFontMetrics(badgeFont);
        int badgeX = HORIZONTAL_PADDING;
        int badgeY = BADGE_TOP_OFFSET;
        int baselineY = badgeY + metrics.getAscent();

        graphics.setColor(new Color(0, 0, 0, 52));
        graphics.drawString(label, badgeX + 2, baselineY + 2);
        graphics.setColor(new Color(255, 255, 255, 236));
        graphics.drawString(label, badgeX, baselineY);
    }

    private static void paintTitle(Graphics2D graphics, String title) {
        int availableWidth = WIDTH - (HORIZONTAL_PADDING * 2);
        int availableHeight = HEIGHT - (VERTICAL_PADDING * 2);
        TextLayout layout = fitTitleLayout(graphics, title, availableWidth, availableHeight);
        FontMetrics metrics = graphics.getFontMetrics(layout.font());
        int lineGap = lineGap(layout.font());
        int totalHeight = (layout.lines().size() * metrics.getHeight()) + ((layout.lines().size() - 1) * lineGap);
        int baselineY = ((HEIGHT - totalHeight) / 2) + metrics.getAscent();

        graphics.setFont(layout.font());
        graphics.setColor(new Color(0, 0, 0, 36));
        for (String line : layout.lines()) {
            graphics.drawString(line, HORIZONTAL_PADDING + 3, baselineY + 3);
            baselineY += metrics.getHeight() + lineGap;
        }

        baselineY = ((HEIGHT - totalHeight) / 2) + metrics.getAscent();
        graphics.setColor(Color.WHITE);
        for (String line : layout.lines()) {
            graphics.drawString(line, HORIZONTAL_PADDING, baselineY);
            baselineY += metrics.getHeight() + lineGap;
        }
    }

    private static TextLayout fitTitleLayout(Graphics2D graphics, String title, int maxWidth, int maxHeight) {
        for (int fontSize = MAX_FONT_SIZE; fontSize >= MIN_FONT_SIZE; fontSize -= FONT_STEP) {
            Font font = selectFont(title, fontSize);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics(font);
            List<String> lines = wrapText(metrics, title, maxWidth);
            int totalHeight = calculateTextHeight(metrics, font, lines.size());
            if (lines.size() <= MAX_TITLE_LINES && totalHeight <= maxHeight) {
                return new TextLayout(font, lines);
            }
        }

        Font fallbackFont = selectFont(title, MIN_FONT_SIZE);
        graphics.setFont(fallbackFont);
        FontMetrics fallbackMetrics = graphics.getFontMetrics(fallbackFont);
        return new TextLayout(fallbackFont, wrapAndClampText(fallbackMetrics, title, maxWidth, MAX_TITLE_LINES));
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

    private static List<String> wrapText(FontMetrics metrics, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty()) {
            int breakIndex = findBreakIndex(metrics, remaining, maxWidth);
            if (breakIndex <= 0) {
                breakIndex = remaining.offsetByCodePoints(0, 1);
            }
            String line = remaining.substring(0, breakIndex).stripTrailing();
            if (!line.isEmpty()) {
                lines.add(line);
            }
            remaining = remaining.substring(breakIndex).stripLeading();
        }
        if (lines.isEmpty()) {
            lines.add(text);
        }
        return lines;
    }

    private static List<String> wrapAndClampText(FontMetrics metrics, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            if (lines.size() == maxLines - 1) {
                lines.add(ellipsize(metrics, remaining, maxWidth));
                return lines;
            }

            int breakIndex = findBreakIndex(metrics, remaining, maxWidth);
            if (breakIndex <= 0) {
                breakIndex = remaining.offsetByCodePoints(0, 1);
            }
            String line = remaining.substring(0, breakIndex).stripTrailing();
            if (!line.isEmpty()) {
                lines.add(line);
            }
            remaining = remaining.substring(breakIndex).stripLeading();
        }

        if (lines.isEmpty()) {
            lines.add(ellipsize(metrics, text, maxWidth));
        }
        return lines;
    }

    private static int findBreakIndex(FontMetrics metrics, String text, int maxWidth) {
        int lastBreakIndex = -1;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int nextIndex = index + Character.charCount(codePoint);
            String candidate = text.substring(0, nextIndex);
            if (metrics.stringWidth(candidate) > maxWidth) {
                return lastBreakIndex > 0 ? lastBreakIndex : index;
            }
            if (isBreakOpportunity(codePoint)) {
                lastBreakIndex = nextIndex;
            }
            index = nextIndex;
        }
        return text.length();
    }

    private static boolean isBreakOpportunity(int codePoint) {
        return Character.isWhitespace(codePoint)
                || "-_/\\|,.，。！？、:：;；)]）】》」』】".indexOf(codePoint) >= 0;
    }

    private static String ellipsize(FontMetrics metrics, String text, int maxWidth) {
        String compact = text.strip();
        if (metrics.stringWidth(compact) <= maxWidth) {
            return compact;
        }

        int endIndex = compact.length();
        while (endIndex > 0) {
            int nextIndex = compact.offsetByCodePoints(0, compact.codePointCount(0, endIndex) - 1);
            String candidate = compact.substring(0, nextIndex).stripTrailing() + ELLIPSIS;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                return candidate;
            }
            endIndex = nextIndex;
        }
        return ELLIPSIS;
    }

    private static int calculateTextHeight(FontMetrics metrics, Font font, int lineCount) {
        return (lineCount * metrics.getHeight()) + ((lineCount - 1) * lineGap(font));
    }

    private static int lineGap(Font font) {
        return Math.max(8, font.getSize() / 7);
    }

    private static GradientSpec gradientFor(String seed) {
        byte[] digest = sha256(seed);
        float hueStart = (digest[0] & 0xFF) / 255f;
        float hueEnd = (hueStart + 0.12f + ((digest[1] & 0xFF) / 255f) * 0.28f) % 1.0f;
        float saturationStart = 0.58f + ((digest[2] & 0xFF) / 255f) * 0.18f;
        float saturationEnd = 0.54f + ((digest[3] & 0xFF) / 255f) * 0.16f;
        float brightnessStart = 0.78f + ((digest[4] & 0xFF) / 255f) * 0.12f;
        float brightnessEnd = 0.66f + ((digest[5] & 0xFF) / 255f) * 0.18f;
        double angle = ((digest[6] & 0xFF) / 255.0) * Math.PI * 2.0;
        double distance = Math.hypot(WIDTH, HEIGHT) / 2.0;
        float centerX = WIDTH / 2f;
        float centerY = HEIGHT / 2f;
        float deltaX = (float) (Math.cos(angle) * distance);
        float deltaY = (float) (Math.sin(angle) * distance);

        return new GradientSpec(
                centerX - deltaX,
                centerY - deltaY,
                centerX + deltaX,
                centerY + deltaY,
                Color.getHSBColor(hueStart, saturationStart, brightnessStart),
                Color.getHSBColor(hueEnd, saturationEnd, brightnessEnd)
        );
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
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

        try (ImageOutputStream outputStream = ImageIO.createImageOutputStream(Files.newOutputStream(targetPath))) {
            writer.setOutput(outputStream);
            writer.write(null, new IIOImage(image, null, null), writeParam);
        } finally {
            writer.dispose();
        }
    }

    private static String displayTitle(String title, String fallbackTitle) {
        String cleaned = title == null ? "" : title.strip();
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        return fallbackTitle == null ? "" : fallbackTitle.strip();
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private record GradientSpec(
            float startX,
            float startY,
            float endX,
            float endY,
            Color startColor,
            Color endColor
    ) {
    }

    private record TextLayout(Font font, List<String> lines) {
    }
}
