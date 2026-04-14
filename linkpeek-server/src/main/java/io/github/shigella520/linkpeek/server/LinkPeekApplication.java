package io.github.shigella520.linkpeek.server;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LinkPeekProperties.class)
public class LinkPeekApplication {
    public static void main(String[] args) {
        SpringApplication.run(LinkPeekApplication.class, args);
    }
}
