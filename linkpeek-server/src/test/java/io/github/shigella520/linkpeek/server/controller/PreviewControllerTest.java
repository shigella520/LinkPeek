package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.server.service.PreviewProviderRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PreviewControllerTest {
    private static final Path TEST_CACHE_DIR;
    private static final Path TEST_STATS_DIR;
    private static final Path TEST_STATS_DB;
    private static final Path TEST_WEB_ICON;

    static {
        try {
            TEST_CACHE_DIR = Files.createTempDirectory("linkpeek-server-cache");
            TEST_STATS_DIR = Files.createTempDirectory("linkpeek-server-stats");
            TEST_STATS_DB = TEST_STATS_DIR.resolve("linkpeek-test.db");
            TEST_WEB_ICON = TEST_STATS_DIR.resolve("favicon.svg");
            Files.writeString(
                    TEST_WEB_ICON,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><circle cx=\"16\" cy=\"16\" r=\"16\" fill=\"#0a84ff\"/></svg>"
            );
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("linkpeek.cache-dir", () -> TEST_CACHE_DIR.toString());
        registry.add("linkpeek.stats-db-path", () -> TEST_STATS_DB.toString());
        registry.add("linkpeek.base-url", () -> "https://preview.example.com");
        registry.add("linkpeek.web-icon-path", () -> TEST_WEB_ICON.toString());
        registry.add("management.endpoints.web.exposure.include", () -> "health");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PreviewProviderRegistry previewProviderRegistry;

    private TestPreviewProvider testPreviewProvider;

    @BeforeEach
    void setUp() throws IOException {
        Files.walk(TEST_CACHE_DIR)
                .filter(path -> !path.equals(TEST_CACHE_DIR))
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        Files.createDirectories(TEST_CACHE_DIR);
        jdbcTemplate.execute("DELETE FROM stats_event");
        jdbcTemplate.execute("DELETE FROM stats_link");

        testPreviewProvider = new TestPreviewProvider();
        when(previewProviderRegistry.findSupporting(argThat(supportedUrl())))
                .thenAnswer(invocation -> Optional.of(testPreviewProvider));
        when(previewProviderRegistry.findSupporting(argThat(uri -> !supportedUrl().matches(uri))))
                .thenReturn(Optional.empty());
        when(previewProviderRegistry.getById("stub"))
                .thenReturn(Optional.of(testPreviewProvider));
    }

    @AfterAll
    static void cleanUp() throws IOException {
        Files.walk(TEST_CACHE_DIR)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        Files.walk(TEST_STATS_DIR)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
    }

    @Test
    void rootRedirectsToDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/dashboard"));
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void actuatorHealthEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiJsonEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"title\":\"LinkPeek API\"")))
                .andExpect(content().string(containsString("\"/preview\"")));
    }

    @Test
    void docHtmlEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/doc.html"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            status == 200 || status == 302,
                            "Expected 200 or 302 for /doc.html but got " + status
                    );
                });
    }

    @Test
    void dashboardPageAndAssetsAreExposed() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("LinkPeek Dashboard")))
                .andExpect(content().string(containsString("Copy LinkPeek URL")))
                .andExpect(content().string(containsString("link-builder-input")))
                .andExpect(content().string(containsString("/favicon.ico")))
                .andExpect(content().string(containsString("https://github.com/shigella520/LinkPeek")));

        mockMvc.perform(get("/dashboard/styles.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));

        mockMvc.perform(get("/dashboard/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("application/javascript")));

        mockMvc.perform(get("/webjars/echarts/5.5.1/dist/echarts.min.js"))
                .andExpect(status().isOk());
    }

    @Test
    void faviconEndpointReturnsConfiguredIcon() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("image/svg+xml")))
                .andExpect(content().string(containsString("<svg")));
    }

    @Test
    void crawlerRequestReturnsOgHtmlAndCachesMetadata() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("og:image")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/media/thumb/" + key().value() + ".jpg")));
    }

    @Test
    void browserRequestRedirectsToOriginalUrl() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://video.example.com/watch/abc"));
    }

    @Test
    void renderModeHeaderCanForceCrawlerHtmlForSwaggerUi() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                        .header("X-LinkPeek-Render-Mode", "crawler"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("og:title")));
    }

    @Test
    void invalidRenderModeHeaderReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header("X-LinkPeek-Render-Mode", "invalid-mode"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidUrlReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "notaurl")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedUrlReturnsUnprocessableEntity() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://unsupported.example.com/post/1")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void dashboardStatsEndpointAggregatesPreviewEvents() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound());

        mockMvc.perform(get("/preview")
                        .param("url", "notaurl")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/stats/dashboard")
                        .param("range", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.createCount.value").value(1))
                .andExpect(jsonPath("$.overview.openCount.value").value(1))
                .andExpect(jsonPath("$.failureBreakdown.invalid").value(1))
                .andExpect(jsonPath("$.topLinks[0].canonicalUrl").value("https://video.example.com/watch/abc"));
    }

    @Test
    void dashboardStatsEndpointRejectsInvalidRange() throws Exception {
        mockMvc.perform(get("/api/stats/dashboard")
                        .param("range", "12h"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thumbnailEndpointDownloadsAndCachesThumbnail() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isOk())
                .andExpect(content().bytes("thumb-data".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(1, testPreviewProvider.thumbnailDownloads.get());
    }

    @Test
    void thumbnailEndpointReturnsNotFoundWhenMetadataIsMissing() throws Exception {
        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void videoEndpointReturnsNotImplemented() throws Exception {
        mockMvc.perform(get("/media/video/{previewKey}.mp4", key().value()))
                .andExpect(status().isNotImplemented());
    }

    private static PreviewKey key() {
        return PreviewKey.fromCanonicalUrl("https://video.example.com/watch/abc");
    }

    private static ArgumentMatcher<URI> supportedUrl() {
        return uri -> uri != null && "video.example.com".equals(uri.getHost());
    }

    private static final class TestPreviewProvider implements PreviewProvider {
        private final AtomicInteger thumbnailDownloads = new AtomicInteger();

        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public boolean supports(URI sourceUrl) {
            return "video.example.com".equals(sourceUrl.getHost());
        }

        @Override
        public URI canonicalize(URI sourceUrl) {
            return URI.create("https://video.example.com/watch/abc");
        }

        @Override
        public PreviewMetadata resolve(URI sourceUrl) {
            return new PreviewMetadata(
                    sourceUrl.toString(),
                    canonicalize(sourceUrl).toString(),
                    getId(),
                    "Stub title",
                    "Stub description",
                    "Stub site",
                    "https://img.example/thumb.jpg",
                    1200,
                    630,
                    ContentType.VIDEO
            );
        }

        @Override
        public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
            thumbnailDownloads.incrementAndGet();
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, "thumb-data");
            return targetPath;
        }
    }
}
