package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.concurrent.TimeUnit;

@Controller
public class DashboardController {
    private static final Resource DEFAULT_FAVICON = new ClassPathResource("static/dashboard/DefaultIcon.svg");

    private final LinkPeekProperties linkPeekProperties;

    public DashboardController(LinkPeekProperties linkPeekProperties) {
        this.linkPeekProperties = linkPeekProperties;
    }

    @GetMapping("/")
    @Hidden
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/dashboard"))
                .build();
    }

    @GetMapping("/dashboard")
    @Hidden
    public ResponseEntity<Resource> dashboard() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/dashboard/index.html"));
    }

    @GetMapping("/favicon.ico")
    @Hidden
    public ResponseEntity<Resource> favicon() {
        Resource resource = resolveFavicon();
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        MediaType contentType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"favicon" + extensionOf(resource) + "\"")
                .contentType(contentType)
                .body(resource);
    }

    private Resource resolveFavicon() {
        if (StringUtils.hasText(linkPeekProperties.getWebIconPath())) {
            Resource configuredIcon = new FileSystemResource(linkPeekProperties.getWebIconPath().trim());
            if (configuredIcon.exists() && configuredIcon.isReadable()) {
                return configuredIcon;
            }
        }
        return DEFAULT_FAVICON;
    }

    private String extensionOf(Resource resource) {
        String filename = resource.getFilename();
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int index = filename.lastIndexOf('.');
        if (index < 0) {
            return "";
        }
        return filename.substring(index);
    }
}
