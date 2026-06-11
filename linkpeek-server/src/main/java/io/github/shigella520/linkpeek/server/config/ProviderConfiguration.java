package io.github.shigella520.linkpeek.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.util.CrawlerMatcher;
import io.github.shigella520.linkpeek.provider.bilibili.BilibiliPreviewProvider;
import io.github.shigella520.linkpeek.provider.gaphub.GapHubPreviewProvider;
import io.github.shigella520.linkpeek.provider.linuxdo.LinuxDoPreviewProvider;
import io.github.shigella520.linkpeek.provider.nga.NgaPreviewProvider;
import io.github.shigella520.linkpeek.provider.v2ex.V2exPreviewProvider;
import io.github.shigella520.linkpeek.server.admin.service.ProviderConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ProviderConfiguration {
    @Bean
    @Primary
    public HttpClient httpClient(LinkPeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean(name = "shareSummaryImageHttpClient")
    public HttpClient shareSummaryImageHttpClient(LinkPeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean(name = "shareSummaryImageExecutor", destroyMethod = "shutdown")
    public ExecutorService shareSummaryImageExecutor() {
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "share-summary-image-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "shareSummaryAudioHttpClient")
    public HttpClient shareSummaryAudioHttpClient(LinkPeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean(name = "shareSummaryAudioExecutor", destroyMethod = "shutdown")
    public ExecutorService shareSummaryAudioExecutor() {
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "share-summary-audio-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "notificationWebhookHttpClient")
    public HttpClient notificationWebhookHttpClient(LinkPeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean(name = "notificationWebhookExecutor", destroyMethod = "shutdown")
    public ExecutorService notificationWebhookExecutor() {
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(
                1,
                2,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                runnable -> {
                    Thread thread = new Thread(runnable, "notification-webhook-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean
    public CrawlerMatcher crawlerMatcher(LinkPeekProperties properties) {
        return new CrawlerMatcher(properties.getCrawlerSignatures());
    }

    @Bean
    public BilibiliPreviewProvider bilibiliPreviewProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            LinkPeekProperties properties
    ) {
        return new BilibiliPreviewProvider(
                httpClient,
                objectMapper,
                URI.create("https://api.bilibili.com"),
                properties.getDownloadTimeout(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        );
    }

    @Bean
    public V2exPreviewProvider v2exPreviewProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            LinkPeekProperties properties
    ) {
        return new V2exPreviewProvider(
                httpClient,
                objectMapper,
                URI.create("https://www.v2ex.com"),
                properties.getDownloadTimeout(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        );
    }

    @Bean
    public GapHubPreviewProvider gapHubPreviewProvider(
            HttpClient httpClient,
            LinkPeekProperties properties
    ) {
        return new GapHubPreviewProvider(
                httpClient,
                URI.create("https://gaphub.cc"),
                properties.getDownloadTimeout(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        );
    }

    @Bean
    public LinuxDoPreviewProvider linuxDoPreviewProvider(
            LinkPeekProperties properties,
            ProviderConfigService providerConfigService,
            ObjectMapper objectMapper
    ) {
        // Linux.do currently challenges Java HTTP/1.1 requests, while HTTP/2 returns the public topic page.
        HttpClient linuxDoHttpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();

        return new LinuxDoPreviewProvider(
                linuxDoHttpClient,
                URI.create("https://linux.do"),
                properties.getDownloadTimeout(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                providerConfigService::linuxDoCookieHeader,
                objectMapper
        );
    }

    @Bean
    public NgaPreviewProvider ngaPreviewProvider(
            HttpClient httpClient,
            LinkPeekProperties properties,
            ProviderConfigService providerConfigService
    ) {
        return new NgaPreviewProvider(
                httpClient,
                URI.create("https://bbs.nga.cn"),
                properties.getDownloadTimeout(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                () -> new NgaPreviewProvider.NgaCredentials(
                        providerConfigService.ngaPassportUid(),
                        providerConfigService.ngaPassportCid()
                )
        );
    }
}
