package io.github.shigella520.linkpeek.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.util.CrawlerMatcher;
import io.github.shigella520.linkpeek.provider.bilibili.BilibiliPreviewProvider;
import io.github.shigella520.linkpeek.provider.nga.NgaPreviewProvider;
import io.github.shigella520.linkpeek.provider.v2ex.V2exPreviewProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;

@Configuration
public class ProviderConfiguration {
    @Bean
    public HttpClient httpClient(LinkPeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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
    public NgaPreviewProvider ngaPreviewProvider(
            HttpClient httpClient,
            LinkPeekProperties properties
    ) {
        return new NgaPreviewProvider(
                httpClient,
                URI.create("https://bbs.nga.cn"),
                properties.getDownloadTimeout(),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                properties.getNgaPassportUid(),
                properties.getNgaPassportCid()
        );
    }
}
