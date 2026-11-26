package io.github.shigella520.linkpeek.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebhookEnvironmentConfigurationTest {
    @Test
    void bindsCommaSeparatedEnvironmentVariableUsingApplicationConfig() {
        context("192.168.31.11, 192.168.31.12").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(List.of("192.168.31.11", "192.168.31.12"),
                    context.getBean(LinkPeekProperties.class).getWebhookAllowedPrivateHosts());
        });
    }

    @Test
    void emptyEnvironmentVariableKeepsPrivateHostsBlocked() {
        context("").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(List.of(), context.getBean(LinkPeekProperties.class).getWebhookAllowedPrivateHosts());
        });
    }

    private ApplicationContextRunner context(String value) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                            "webhookTestEnvironment", Map.of("WEBHOOK_ALLOWED_PRIVATE_HOSTS", value)));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withUserConfiguration(PropertiesConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LinkPeekProperties.class)
    static class PropertiesConfiguration {
    }
}
