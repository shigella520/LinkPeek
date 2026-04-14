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

    static {
        try {
            TEST_CACHE_DIR = Files.createTempDirectory("linkpeek-server-cache");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("linkpeek.cache-dir", () -> TEST_CACHE_DIR.toString());
        registry.add("linkpeek.base-url", () -> "https://preview.example.com");
        registry.add("management.endpoints.web.exposure.include", () -> "health");
    }

    @Autowired
    private MockMvc mockMvc;

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
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/"))
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
