package io.github.shigella520.linkpeek.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class PreviewWarmupConfiguration {
    @Bean(name = "previewWarmupExecutor")
    public ThreadPoolTaskExecutor previewWarmupExecutor(LinkPeekProperties properties) {
        int threads = Math.max(1, properties.getPreviewWarmupThreads());
        int queueCapacity = Math.max(0, properties.getPreviewWarmupQueueCapacity());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("linkpeek-warmup-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
