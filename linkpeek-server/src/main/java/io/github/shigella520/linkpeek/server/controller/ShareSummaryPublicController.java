package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryAudioService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryImageService;
import io.github.shigella520.linkpeek.server.render.ShareSummaryReportPageRenderer;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/share-summary")
@Hidden
public class ShareSummaryPublicController {
    private static final Logger log = LoggerFactory.getLogger(ShareSummaryPublicController.class);

    private final ShareSummaryImageService shareSummaryImageService;
    private final ShareSummaryAudioService shareSummaryAudioService;
    private final ShareSummaryReportPageRenderer reportPageRenderer;

    public ShareSummaryPublicController(
            ShareSummaryImageService shareSummaryImageService,
            ShareSummaryAudioService shareSummaryAudioService,
            ShareSummaryReportPageRenderer reportPageRenderer
    ) {
        this.shareSummaryImageService = shareSummaryImageService;
        this.shareSummaryAudioService = shareSummaryAudioService;
        this.reportPageRenderer = reportPageRenderer;
    }

    @GetMapping("/og-images/{publicToken}.{ext}")
    public ResponseEntity<Resource> ogImage(@PathVariable String publicToken, @PathVariable String ext) {
        try {
            ShareSummaryImageService.PublicImage image = shareSummaryImageService.publicImage(publicToken, ext);
            return ResponseEntity.ok()
                    .contentType(image.mediaType())
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                    .body(image.resource());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping(value = "/reports/{publicToken}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable String publicToken) {
        try {
            ShareSummaryImageService.PublicReport report = shareSummaryImageService.publicReport(publicToken);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(reportHtml(report.image(), report.run()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/audios/{publicToken}.{ext}")
    public ResponseEntity<Resource> audio(
            @PathVariable String publicToken,
            @PathVariable String ext,
            HttpServletRequest request
    ) {
        try {
            ShareSummaryAudioService.PublicAudio audio = shareSummaryAudioService.publicAudio(publicToken, ext);
            logAudioRequest(request, publicToken, ext, audio);
            return ResponseEntity.ok()
                    .contentType(audio.mediaType())
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                    .body(audio.resource());
        } catch (IllegalArgumentException exception) {
            logAudioRequestNotFound(request, publicToken, ext, exception);
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/audios/{publicToken}.{ext}/plays")
    public ShareSummaryAudioService.PlayCountResponse recordAudioPlay(
            @PathVariable String publicToken,
            @PathVariable String ext
    ) {
        try {
            return shareSummaryAudioService.recordPublicPlay(publicToken, ext);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private String reportHtml(ShareSummaryImageRecord image, ShareSummaryRunRecord run) {
        ShareSummaryAudioService.AudioSummary audioSummary = shareSummaryAudioService.audioSummary(run.getId());
        return reportPageRenderer.render(
                image,
                run.getReport(),
                audioSummary == null ? "" : audioSummary.audioUrl(),
                audioSummary == null ? null : audioSummary.audioMediaType()
        );
    }

    private void logAudioRequest(
            HttpServletRequest request,
            String publicToken,
            String ext,
            ShareSummaryAudioService.PublicAudio audio
    ) {
        log.info(
                "share_summary_public_audio_request tokenSuffix={} ext={} mediaType={} contentLength={} range={} ifRange={} userAgent={} referer={} accept={} forwardedFor={} realIp={} remoteAddr={}",
                tokenSuffix(publicToken),
                ext,
                audio.mediaType(),
                contentLength(audio.resource()),
                header(request, "Range"),
                header(request, "If-Range"),
                header(request, "User-Agent"),
                header(request, "Referer"),
                header(request, "Accept"),
                header(request, "X-Forwarded-For"),
                header(request, "X-Real-IP"),
                request.getRemoteAddr()
        );
    }

    private void logAudioRequestNotFound(
            HttpServletRequest request,
            String publicToken,
            String ext,
            IllegalArgumentException exception
    ) {
        log.info(
                "share_summary_public_audio_not_found tokenSuffix={} ext={} range={} userAgent={} referer={} accept={} forwardedFor={} realIp={} remoteAddr={} message={}",
                tokenSuffix(publicToken),
                ext,
                header(request, "Range"),
                header(request, "User-Agent"),
                header(request, "Referer"),
                header(request, "Accept"),
                header(request, "X-Forwarded-For"),
                header(request, "X-Real-IP"),
                request.getRemoteAddr(),
                exception.getMessage()
        );
    }

    private long contentLength(Resource resource) {
        try {
            return resource.contentLength();
        } catch (IOException exception) {
            return -1L;
        }
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value : "-";
    }

    private String tokenSuffix(String publicToken) {
        if (!StringUtils.hasText(publicToken)) {
            return "-";
        }
        String value = publicToken.strip();
        return value.length() <= 8 ? value : value.substring(value.length() - 8);
    }
}
