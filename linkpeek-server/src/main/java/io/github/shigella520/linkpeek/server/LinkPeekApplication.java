package io.github.shigella520.linkpeek.server;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("io.github.shigella520.linkpeek.server.stats.persistence")
@EnableScheduling
@EnableConfigurationProperties(LinkPeekProperties.class)
public class LinkPeekApplication {
    public static void main(String[] args) {
        SpringApplication.run(LinkPeekApplication.class, args);
    }
}
