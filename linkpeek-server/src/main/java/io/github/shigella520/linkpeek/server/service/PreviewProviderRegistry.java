package io.github.shigella520.linkpeek.server.service;

import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@Component
public class PreviewProviderRegistry {
    private final List<PreviewProvider> providers;

    public PreviewProviderRegistry(List<PreviewProvider> providers) {
        this.providers = providers;
    }

    public Optional<PreviewProvider> findSupporting(URI sourceUrl) {
        return providers.stream()
                .filter(provider -> provider.supports(sourceUrl))
                .findFirst();
    }

    public Optional<PreviewProvider> getById(String providerId) {
        return providers.stream()
                .filter(provider -> provider.getId().equals(providerId))
                .findFirst();
    }
}
