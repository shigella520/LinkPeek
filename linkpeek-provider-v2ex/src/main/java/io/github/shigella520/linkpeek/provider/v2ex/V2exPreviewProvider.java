package io.github.shigella520.linkpeek.provider.v2ex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.core.util.UrlNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.net.ssl.SSLHandshakeException;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class V2exPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(V2exPreviewProvider.class);

    private static final Pattern TOPIC_PATH_PATTERN = Pattern.compile("^/(?:amp/)?t/(\\d+)(?:/.*)?$");
    private static final String SITE_NAME = "V2EX";
    private static final String REFERER = "https://www.v2ex.com";
    private static final String TITLE_CARD_PREFIX = "generated://v2ex/title-card/";
    private static final int CARD_WIDTH = 1200;
    private static final int CARD_HEIGHT = 630;
    private static final int HORIZONTAL_PADDING = 92;
    private static final int VERTICAL_PADDING = 72;
    private static final int MAX_TITLE_LINES = 3;
    private static final int MAX_FONT_SIZE = 86;
    private static final int MIN_FONT_SIZE = 42;
    private static final int FONT_STEP = 4;
    private static final int MAX_DESCRIPTION_LENGTH = 280;
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

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI apiBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;

    public V2exPreviewProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI apiBaseUri,
            Duration requestTimeout,
            String userAgent
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiBaseUri = apiBaseUri;
        this.requestTimeout = requestTimeout;
        this.userAgent = userAgent;
    }

    @Override
    public String getId() {
        return "v2ex";
    }

    @Override
    public boolean supports(URI sourceUrl) {
        try {
            URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
            return isSupportedHost(normalized) && extractTopicId(normalized).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public URI canonicalize(URI sourceUrl) {
        URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        if (!isSupportedHost(normalized)) {
            throw new UnsupportedPreviewUrlException("Only V2EX topic URLs are supported.");
        }

        String topicId = extractTopicId(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only V2EX topic URLs are supported."));
        return URI.create("https://www.v2ex.com/t/" + topicId);
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        URI canonicalUrl = canonicalize(sourceUrl);
        String topicId = extractTopicId(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only V2EX topic URLs are supported."));
        URI requestUri = apiBaseUri.resolve("/api/topics/show.json?id=" + URLEncoder.encode(topicId, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Referer", REFERER)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("V2EX API returned HTTP " + response.statusCode());
            }

            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode topic = firstTopic(payload)
                    .orElseThrow(() -> new UpstreamFetchException("V2EX topic was not found."));
            if (topic.path("deleted").asInt(0) != 0) {
                throw new UpstreamFetchException("V2EX topic is no longer available.");
            }

            JsonNode node = topic.path("node");
            JsonNode member = topic.path("member");
            String topicTitle = clean(topic.path("title").asText(""));

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    topicTitle.isBlank() ? SITE_NAME : topicTitle,
                    buildDescription(
                            clean(node.path("title").asText("")),
                            clean(member.path("username").asText("")),
                            clean(topic.path("content").asText(""))
                    ),
                    SITE_NAME,
                    buildGeneratedThumbnailUrl(topicId),
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while calling the V2EX API.", exception);
        } catch (IOException exception) {
            log.warn(
                    "v2ex_api_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to read or parse the V2EX API response.");
        }
    }

    @Override
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        if (isGeneratedTitleCard(metadata.thumbnailUrl())) {
            renderTitleCard(metadata, targetPath);
            return targetPath;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(metadata.thumbnailUrl()))
                .timeout(requestTimeout)
                .header("Referer", REFERER)
                .header("User-Agent", userAgent)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("Thumbnail request failed with HTTP " + response.statusCode());
            }
            Files.write(targetPath, response.body());
            return targetPath;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while downloading the thumbnail.", exception);
        }
    }

    private void renderTitleCard(PreviewMetadata metadata, Path targetPath) throws IOException {
        BufferedImage image = new BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            applyQualityHints(graphics);
            paintBackground(graphics, metadata.canonicalUrl());
            paintTitle(graphics, displayTitle(metadata.title()));
        } finally {
            graphics.dispose();
        }
        writeJpeg(image, targetPath);
    }

    private void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private void paintBackground(Graphics2D graphics, String canonicalUrl) {
        GradientSpec gradient = gradientFor(canonicalUrl);
        graphics.setPaint(new GradientPaint(
                gradient.startX(),
                gradient.startY(),
                gradient.startColor(),
                gradient.endX(),
                gradient.endY(),
                gradient.endColor()
        ));
        graphics.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);

        graphics.setColor(withAlpha(gradient.startColor(), 56));
        graphics.fill(new Ellipse2D.Double(-140, -120, 520, 520));

        graphics.setColor(withAlpha(gradient.endColor(), 48));
        graphics.fill(new Ellipse2D.Double(CARD_WIDTH - 360, CARD_HEIGHT - 320, 460, 460));

        graphics.setColor(new Color(255, 255, 255, 24));
        graphics.fill(new Ellipse2D.Double(CARD_WIDTH * 0.48, -110, 340, 340));
    }

    private void paintTitle(Graphics2D graphics, String title) {
        int availableWidth = CARD_WIDTH - (HORIZONTAL_PADDING * 2);
        int availableHeight = CARD_HEIGHT - (VERTICAL_PADDING * 2);
        TextLayout layout = fitTitleLayout(graphics, title, availableWidth, availableHeight);
        FontMetrics metrics = graphics.getFontMetrics(layout.font());
        int lineGap = lineGap(layout.font());
        int totalHeight = (layout.lines().size() * metrics.getHeight()) + ((layout.lines().size() - 1) * lineGap);
        int baselineY = ((CARD_HEIGHT - totalHeight) / 2) + metrics.getAscent();

        graphics.setFont(layout.font());
        graphics.setColor(new Color(0, 0, 0, 36));
        for (String line : layout.lines()) {
            graphics.drawString(line, HORIZONTAL_PADDING + 3, baselineY + 3);
            baselineY += metrics.getHeight() + lineGap;
        }

        baselineY = ((CARD_HEIGHT - totalHeight) / 2) + metrics.getAscent();
        graphics.setColor(Color.WHITE);
        for (String line : layout.lines()) {
            graphics.drawString(line, HORIZONTAL_PADDING, baselineY);
            baselineY += metrics.getHeight() + lineGap;
        }
    }

    private TextLayout fitTitleLayout(Graphics2D graphics, String title, int maxWidth, int maxHeight) {
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

    private Font selectFont(String text, int fontSize) {
        for (String family : FONT_FAMILIES) {
            Font font = new Font(family, TITLE_FONT_STYLE, fontSize);
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, TITLE_FONT_STYLE, fontSize);
    }

    private List<String> wrapText(FontMetrics metrics, String text, int maxWidth) {
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

    private List<String> wrapAndClampText(FontMetrics metrics, String text, int maxWidth, int maxLines) {
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

    private int findBreakIndex(FontMetrics metrics, String text, int maxWidth) {
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

    private boolean isBreakOpportunity(int codePoint) {
        return Character.isWhitespace(codePoint)
                || "-_/\\|,.，。！？、:：;；)]）】》」』】".indexOf(codePoint) >= 0;
    }

    private String ellipsize(FontMetrics metrics, String text, int maxWidth) {
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

    private int calculateTextHeight(FontMetrics metrics, Font font, int lineCount) {
        return (lineCount * metrics.getHeight()) + ((lineCount - 1) * lineGap(font));
    }

    private int lineGap(Font font) {
        return Math.max(8, font.getSize() / 7);
    }

    private GradientSpec gradientFor(String canonicalUrl) {
        byte[] digest = sha256(canonicalUrl);
        float hueStart = (digest[0] & 0xFF) / 255f;
        float hueEnd = (hueStart + 0.12f + ((digest[1] & 0xFF) / 255f) * 0.28f) % 1.0f;
        float saturationStart = 0.58f + ((digest[2] & 0xFF) / 255f) * 0.18f;
        float saturationEnd = 0.54f + ((digest[3] & 0xFF) / 255f) * 0.16f;
        float brightnessStart = 0.78f + ((digest[4] & 0xFF) / 255f) * 0.12f;
        float brightnessEnd = 0.66f + ((digest[5] & 0xFF) / 255f) * 0.18f;
        double angle = ((digest[6] & 0xFF) / 255.0) * Math.PI * 2.0;
        double distance = Math.hypot(CARD_WIDTH, CARD_HEIGHT) / 2.0;
        float centerX = CARD_WIDTH / 2f;
        float centerY = CARD_HEIGHT / 2f;
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

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void writeJpeg(BufferedImage image, Path targetPath) throws IOException {
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

    private boolean isSupportedHost(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return "v2ex.com".equals(host) || "www.v2ex.com".equals(host);
    }

    private Optional<String> extractTopicId(URI sourceUrl) {
        Matcher matcher = TOPIC_PATH_PATTERN.matcher(sourceUrl.getPath());
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<JsonNode> firstTopic(JsonNode payload) {
        if (payload == null || !payload.isArray() || payload.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(payload.get(0));
    }

    private String buildGeneratedThumbnailUrl(String topicId) {
        return TITLE_CARD_PREFIX + topicId;
    }

    private boolean isGeneratedTitleCard(String thumbnailUrl) {
        return thumbnailUrl != null && thumbnailUrl.startsWith(TITLE_CARD_PREFIX);
    }

    private String displayTitle(String title) {
        String cleaned = clean(title);
        return cleaned.isBlank() ? SITE_NAME : cleaned;
    }

    private String buildDescription(String nodeTitle, String username, String content) {
        List<String> parts = new ArrayList<>();
        if (!nodeTitle.isBlank()) {
            parts.add(nodeTitle);
        }
        if (!username.isBlank()) {
            parts.add("@" + username);
        }
        if (!content.isBlank()) {
            parts.add(summarize(content));
        }

        String description = String.join(" - ", parts);
        return description.isBlank() ? SITE_NAME : description;
    }

    private String summarize(String value) {
        String compact = clean(value);
        if (compact.length() <= MAX_DESCRIPTION_LENGTH) {
            return compact;
        }
        return compact.substring(0, MAX_DESCRIPTION_LENGTH - 1).stripTrailing() + ELLIPSIS;
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private UpstreamFetchException translateIOException(IOException exception, String fallbackMessage) {
        if (isCausedBy(exception, SSLHandshakeException.class)) {
            return new UpstreamFetchException(
                    "TLS handshake with the V2EX upstream failed. Check the Java trust store or your proxy certificate.",
                    exception
            );
        }
        return new UpstreamFetchException(fallbackMessage, exception);
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> targetType) {
        Throwable current = throwable;
        while (current != null) {
            if (targetType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
