package io.github.shigella520.linkpeek.core.media;

import io.github.shigella520.linkpeek.core.util.CardTextSanitizer;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
    private static final int WATERMARK_TILE_WIDTH = 250;
    private static final int WATERMARK_TILE_HEIGHT = 140;
    private static final int WATERMARK_SPACING_X = 200;
    private static final int WATERMARK_SPACING_Y = 126;
    private static final double WATERMARK_ROTATION_RADIANS = Math.toRadians(-21);
    private static final float WATERMARK_ALPHA = 0.16f;
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
        render(title, fallbackTitle, seed, badgeLabel, null, targetPath);
    }

    public static void render(
            String title,
            String fallbackTitle,
            String seed,
            String badgeLabel,
            Watermark watermark,
            Path targetPath
    ) throws IOException {
        Files.createDirectories(targetPath.getParent());

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            applyQualityHints(graphics);
            paintBackground(graphics, seed);
            paintWatermark(graphics, watermark);
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

    private static void paintWatermark(Graphics2D graphics, Watermark watermark) throws IOException {
        if (watermark == null) {
            return;
        }
        BufferedImage watermarkImage = removeWatermarkColor(loadWatermarkImage(watermark));
        if (watermarkImage == null) {
            return;
        }
        int sourceWidth = watermarkImage.getWidth();
        int sourceHeight = watermarkImage.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        double scale = Math.min(
                watermark.tileWidth() / (double) sourceWidth,
                watermark.tileHeight() / (double) sourceHeight
        );
        int drawWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        int cellWidth = watermark.tileWidth() + watermark.spacingX();
        int cellHeight = watermark.tileHeight() + watermark.spacingY();

        Composite originalComposite = graphics.getComposite();
        AffineTransform originalTransform = graphics.getTransform();
        try {
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, watermark.alpha()));
            graphics.rotate(watermark.rotationRadians(), WIDTH / 2.0, HEIGHT / 2.0);
            for (int y = -HEIGHT; y < HEIGHT * 2; y += cellHeight) {
                int rowOffset = Math.floorMod(y / cellHeight, 2) * (cellWidth / 2);
                for (int x = -WIDTH; x < WIDTH * 2; x += cellWidth) {
                    int tileX = x + rowOffset + ((watermark.tileWidth() - drawWidth) / 2);
                    int tileY = y + ((watermark.tileHeight() - drawHeight) / 2);
                    graphics.drawImage(watermarkImage, tileX, tileY, drawWidth, drawHeight, null);
                }
            }
        } finally {
            graphics.setTransform(originalTransform);
            graphics.setComposite(originalComposite);
        }
    }

    private static void paintBadge(Graphics2D graphics, String badgeLabel) {
        String label = CardTextSanitizer.sanitize(badgeLabel);
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

    static TextLayout fitTitleLayout(Graphics2D graphics, String title, int maxWidth, int maxHeight) {
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
                || isCjkCodePoint(codePoint)
                || "-_/\\|,.，。！？、:：;；)]）】》」』】".indexOf(codePoint) >= 0;
    }

    private static boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
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
        return CardTextSanitizer.displayTitle(title, fallbackTitle);
    }

    private static BufferedImage loadWatermarkImage(Watermark watermark) throws IOException {
        byte[] bytes;
        try (InputStream inputStream = watermark.ownerType().getResourceAsStream(watermark.resourcePath())) {
            if (inputStream == null) {
                throw new IOException("Title card watermark resource was not found: "
                        + watermark.ownerType().getName() + " " + watermark.resourcePath());
            }
            bytes = inputStream.readAllBytes();
        }
        String lowerPath = watermark.resourcePath().toLowerCase();
        if (lowerPath.endsWith(".svg")) {
            return readSvg(bytes);
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("Unsupported title card watermark image: " + watermark.resourcePath());
            }
            return image;
        }
    }

    private static BufferedImage readSvg(byte[] bytes) throws IOException {
        BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            transcoder.transcode(new TranscoderInput(inputStream), null);
        } catch (TranscoderException exception) {
            throw new IOException("Failed to render SVG title card watermark.", exception);
        }
        BufferedImage image = transcoder.image();
        if (image == null) {
            throw new IOException("SVG title card watermark did not produce an image.");
        }
        return image;
    }

    static BufferedImage removeWatermarkColor(BufferedImage source) {
        BufferedImage grayscale = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                int gray = Math.round((red * 0.299f) + (green * 0.587f) + (blue * 0.114f));
                grayscale.setRGB(x, y, (alpha << 24) | (gray << 16) | (gray << 8) | gray);
            }
        }
        return grayscale;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public record Watermark(
            Class<?> ownerType,
            String resourcePath,
            int tileWidth,
            int tileHeight,
            int spacingX,
            int spacingY,
            double rotationRadians,
            float alpha
    ) {
        public Watermark {
            if (ownerType == null) {
                throw new IllegalArgumentException("Watermark owner type must not be null.");
            }
            if (resourcePath == null || resourcePath.isBlank()) {
                throw new IllegalArgumentException("Watermark resource path must not be blank.");
            }
            if (tileWidth <= 0 || tileHeight <= 0 || spacingX < 0 || spacingY < 0) {
                throw new IllegalArgumentException("Watermark tile dimensions and spacing are invalid.");
            }
            if (alpha <= 0f || alpha > 1f) {
                throw new IllegalArgumentException("Watermark alpha must be greater than 0 and no more than 1.");
            }
        }

        public static Watermark resource(Class<?> ownerType, String resourcePath) {
            return new Watermark(
                    ownerType,
                    resourcePath,
                    WATERMARK_TILE_WIDTH,
                    WATERMARK_TILE_HEIGHT,
                    WATERMARK_SPACING_X,
                    WATERMARK_SPACING_Y,
                    WATERMARK_ROTATION_RADIANS,
                    WATERMARK_ALPHA
            );
        }
    }

    private static final class BufferedImageTranscoder extends ImageTranscoder {
        private BufferedImage image;

        @Override
        public BufferedImage createImage(int width, int height) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(BufferedImage image, TranscoderOutput output) {
            this.image = image;
        }

        BufferedImage image() {
            return image;
        }
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

    record TextLayout(Font font, List<String> lines) {
    }
}
