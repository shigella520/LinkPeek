package io.github.shigella520.linkpeek.provider.template;

import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;

import java.net.URI;

public class TemplatePreviewProvider implements PreviewProvider {
    @Override
    public String getId() {
        return "template";
    }

    @Override
    public boolean supports(URI sourceUrl) {
        return false;
    }

    @Override
    public URI canonicalize(URI sourceUrl) {
        throw new UnsupportedPreviewUrlException("Replace the template provider with a real implementation.");
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        throw new UnsupportedPreviewUrlException("Replace the template provider with a real implementation.");
    }

    public PreviewMetadata sampleMetadata(URI sourceUrl) {
        return new PreviewMetadata(
                sourceUrl.toString(),
                sourceUrl.toString(),
                getId(),
                "Example title",
                "Example description",
                "Example site",
                "https://example.com/example.jpg",
                1200,
                630,
                ContentType.GENERIC
        );
    }
}
