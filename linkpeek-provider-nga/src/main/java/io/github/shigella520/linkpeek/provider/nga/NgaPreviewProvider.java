package io.github.shigella520.linkpeek.provider.nga;

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
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NgaPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(NgaPreviewProvider.class);

    private static final Pattern TID_QUERY_PATTERN = Pattern.compile("(^|&)tid=(\\d+)($|&)");
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_OG_TITLE_PATTERN = Pattern.compile("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>");
    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>");
    private static final Pattern POSTER_INFO_PATTERN = Pattern.compile("(?is)class=[\"'][^\"']*posterinfo[^\"']*[\"'][\\s\\S]{0,1000}?<a[^>]*>(.*?)</a>");
    private static final Pattern POST_CONTENT_PATTERN = Pattern.compile("(?is)(<(?:(?:span)|(?:div)|(?:td))[^>]*class=[\"'][^\"']*postcontent[^\"']*[\"'][^>]*>[\\s\\S]{0,8000}?</(?:(?:span)|(?:div)|(?:td))>)");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    private static final String CANONICAL_HOST = "bbs.nga.cn";
    private static final String SITE_NAME = "NGA";
    private static final String TITLE_CARD_PREFIX = "generated://nga/thread-card/";
    private static final String ELLIPSIS = "…";
    private static final int CARD_WIDTH = 1200;
    private static final int CARD_HEIGHT = 630;
    private static final int MAX_DESCRIPTION_LENGTH = 220;
    private static final float JPEG_QUALITY = 0.92f;

    private final HttpClient httpClient;
    private final URI pageBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;
    private final String ngaPassportUid;
    private final String ngaPassportCid;

    public NgaPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent,
            String ngaPassportUid,
            String ngaPassportCid
    ) {
        this.httpClient = httpClient;
        this.pageBaseUri = pageBaseUri;
        this.requestTimeout = requestTimeout;
        this.userAgent = userAgent;
        this.ngaPassportUid = trimToNull(ngaPassportUid);
        this.ngaPassportCid = trimToNull(ngaPassportCid);
    }

    @Override
    public String getId() {
        return "nga";
    }

    @Override
    public boolean supports(URI sourceUrl) {
        try {
            URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
            return isSupportedHost(normalized) && isReadPath(normalized) && extractTid(normalized).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public URI canonicalize(URI sourceUrl) {
        URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        if (!isSupportedHost(normalized) || !isReadPath(normalized)) {
            throw new UnsupportedPreviewUrlException("Only NGA thread read.php URLs are supported.");
        }

        String tid = extractTid(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only NGA thread read.php URLs are supported."));
        return URI.create("https://" + CANONICAL_HOST + "/read.php?tid=" + tid);
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        URI canonicalUrl = canonicalize(sourceUrl);
        String tid = extractTid(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only NGA thread read.php URLs are supported."));
        URI requestUri = pageBaseUri.resolve("/read.php?tid=" + tid);

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", pageBaseUri.toString())
                .header("Cookie", buildCookieHeader())
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("NGA page returned HTTP " + response.statusCode());
            }

            String html = decodeHtml(response.body());
            String title = extractTitle(html);
            String author = extractAuthor(html);
            String content = extractFirstPostText(html);
            String description = buildDescription(author, content, extractMetaDescription(html));

            if (title.isBlank() && description.isBlank()) {
                throw new UpstreamFetchException("Failed to parse NGA thread metadata from the page.");
            }

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    title.isBlank() ? "NGA 帖子 " + tid : title,
                    description.isBlank() ? SITE_NAME : description,
                    SITE_NAME,
                    TITLE_CARD_PREFIX + tid,
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while fetching the NGA page.", exception);
        } catch (IOException exception) {
            log.warn(
                    "nga_page_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to fetch or parse the NGA page.");
        }
    }

    @Override
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        renderTitleCard(metadata, targetPath);
        return targetPath;
    }

    private void renderTitleCard(PreviewMetadata metadata, Path targetPath) throws IOException {
        BufferedImage image = new BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            Gradient gradient = gradientFor(metadata.canonicalUrl());
            graphics.setPaint(new GradientPaint(
                    gradient.startX(),
                    gradient.startY(),
                    gradient.startColor(),
                    gradient.endX(),
                    gradient.endY(),
                    gradient.endColor()
            ));
            graphics.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);

            graphics.setColor(new Color(255, 255, 255, 52));
            graphics.fill(new RoundRectangle2D.Double(72, 72, CARD_WIDTH - 144, CARD_HEIGHT - 144, 36, 36));

            graphics.setColor(new Color(36, 41, 49));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString("NGA 帖子预览", 108, 144);

            drawWrappedTitle(graphics, displayTitle(metadata.title()), 108, 228, CARD_WIDTH - 216, 3);

            graphics.setColor(new Color(62, 69, 79));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
            graphics.drawString(ellipsize(graphics.getFontMetrics(), metadata.description(), CARD_WIDTH - 216), 108, 538);
        } finally {
            graphics.dispose();
        }

        writeJpeg(image, targetPath);
    }

    private void drawWrappedTitle(Graphics2D graphics, String title, int x, int y, int maxWidth, int maxLines) {
        for (int fontSize = 64; fontSize >= 40; fontSize -= 4) {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics(font);
            List<String> lines = wrapText(metrics, title, maxWidth, maxLines);
            if (lines.size() <= maxLines) {
                graphics.setColor(new Color(24, 29, 35));
                int currentY = y;
                for (String line : lines) {
                    graphics.drawString(line, x, currentY);
                    currentY += metrics.getHeight() + 10;
                }
                return;
            }
        }
    }

    private List<String> wrapText(FontMetrics metrics, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isBlank() && lines.size() < maxLines) {
            int breakIndex = findBreakIndex(metrics, remaining, maxWidth);
            if (breakIndex <= 0 || breakIndex >= remaining.length()) {
                lines.add(lines.size() == maxLines - 1 ? ellipsize(metrics, remaining, maxWidth) : remaining);
                remaining = "";
            } else {
                String line = remaining.substring(0, breakIndex).strip();
                remaining = remaining.substring(breakIndex).strip();
                if (lines.size() == maxLines - 1 && !remaining.isBlank()) {
                    line = ellipsize(metrics, line + " " + remaining, maxWidth);
                    remaining = "";
                }
                lines.add(line);
            }
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
            if (Character.isWhitespace(codePoint) || "，。！？、,:;)]）】》」』-_/\\".indexOf(codePoint) >= 0) {
                lastBreakIndex = nextIndex;
            }
            index = nextIndex;
        }
        return text.length();
    }

    private String ellipsize(FontMetrics metrics, String value, int maxWidth) {
        String compact = cleanText(value);
        if (compact.isBlank() || metrics.stringWidth(compact) <= maxWidth) {
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

    private Gradient gradientFor(String canonicalUrl) {
        byte[] digest = sha256(canonicalUrl);
        float hueStart = (digest[0] & 0xFF) / 255f;
        float hueEnd = (hueStart + 0.18f + ((digest[1] & 0xFF) / 255f) * 0.18f) % 1.0f;
        return new Gradient(
                0f,
                0f,
                CARD_WIDTH,
                CARD_HEIGHT,
                Color.getHSBColor(hueStart, 0.55f, 0.96f),
                Color.getHSBColor(hueEnd, 0.46f, 0.84f)
        );
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean isSupportedHost(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return "bbs.nga.cn".equals(host) || "nga.178.com".equals(host) || "ngabbs.com".equals(host);
    }

    private boolean isReadPath(URI sourceUrl) {
        return "/read.php".equals(sourceUrl.getPath());
    }

    private Optional<String> extractTid(URI sourceUrl) {
        String query = sourceUrl.getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TID_QUERY_PATTERN.matcher(query);
        if (matcher.find()) {
            return Optional.of(matcher.group(2));
        }
        return Optional.empty();
    }

    private String extractTitle(String html) {
        return firstGroup(META_OG_TITLE_PATTERN, html)
                .or(() -> firstGroup(TITLE_TAG_PATTERN, html))
                .map(this::cleanTitle)
                .orElse("");
    }

    private String extractMetaDescription(String html) {
        return firstGroup(META_DESCRIPTION_PATTERN, html)
                .map(this::decodeHtmlEntities)
                .map(this::cleanText)
                .orElse("");
    }

    private String extractAuthor(String html) {
        return firstGroup(POSTER_INFO_PATTERN, html)
                .map(this::stripTags)
                .map(this::cleanText)
                .orElse("");
    }

    private String extractFirstPostText(String html) {
        return firstGroup(POST_CONTENT_PATTERN, html)
                .map(this::stripTags)
                .map(this::cleanText)
                .orElse("");
    }

    private Optional<String> firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private String cleanTitle(String rawTitle) {
        String title = cleanText(rawTitle)
                .replace("NGA玩家社区", "")
                .replace("NGA 论坛", "")
                .replace("-  ", "")
                .strip();
        if (title.endsWith("-")) {
            title = title.substring(0, title.length() - 1).stripTrailing();
        }
        return title;
    }

    private String buildDescription(String author, String content, String metaDescription) {
        List<String> parts = new ArrayList<>();
        if (!author.isBlank()) {
            parts.add("@" + author);
        }
        if (!content.isBlank()) {
            parts.add(summarize(content));
        } else if (!metaDescription.isBlank()) {
            parts.add(summarize(metaDescription));
        }
        return String.join(" - ", parts);
    }

    private String summarize(String value) {
        String compact = cleanText(value);
        if (compact.length() <= MAX_DESCRIPTION_LENGTH) {
            return compact;
        }
        return compact.substring(0, MAX_DESCRIPTION_LENGTH - 1).stripTrailing() + ELLIPSIS;
    }

    private String stripTags(String htmlFragment) {
        String withoutScripts = SCRIPT_STYLE_PATTERN.matcher(htmlFragment).replaceAll(" ");
        String withoutComments = COMMENT_PATTERN.matcher(withoutScripts).replaceAll(" ");
        String withLineBreaks = withoutComments
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)</li>", "\n");
        return decodeHtmlEntities(TAG_PATTERN.matcher(withLineBreaks).replaceAll(" "));
    }

    private String decodeHtmlEntities(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String decoded = value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
        return decodeNumericEntities(decoded);
    }

    private String decodeNumericEntities(String value) {
        Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(value);
        StringBuilder builder = new StringBuilder();
        int lastIndex = 0;
        while (matcher.find()) {
            builder.append(value, lastIndex, matcher.start());
            String token = matcher.group(1);
            int codePoint = token.startsWith("x") || token.startsWith("X")
                    ? Integer.parseInt(token.substring(1), 16)
                    : Integer.parseInt(token, 10);
            builder.append(Character.toChars(codePoint));
            lastIndex = matcher.end();
        }
        builder.append(value.substring(lastIndex));
        return builder.toString();
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String displayTitle(String title) {
        String cleaned = cleanText(title);
        return cleaned.isBlank() ? SITE_NAME : cleaned;
    }

    private String decodeHtml(byte[] body) {
        String gbkHtml = new String(body, Charset.forName("GB18030"));
        if (gbkHtml.contains("charset=utf-8") || gbkHtml.contains("charset=UTF-8")) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return gbkHtml;
    }

    private String buildCookieHeader() {
        if (ngaPassportUid != null && ngaPassportCid != null) {
            return "ngaPassportUid=" + ngaPassportUid + "; ngaPassportCid=" + ngaPassportCid + ";";
        }
        return "guestJs=" + Instant.now().getEpochSecond();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UpstreamFetchException translateIOException(IOException exception, String fallbackMessage) {
        if (isCausedBy(exception, SSLHandshakeException.class)) {
            return new UpstreamFetchException(
                    "TLS handshake with the NGA upstream failed. Check the Java trust store or your proxy certificate.",
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

    private record Gradient(
            float startX,
            float startY,
            float endX,
            float endY,
            Color startColor,
            Color endColor
    ) {
    }
}
