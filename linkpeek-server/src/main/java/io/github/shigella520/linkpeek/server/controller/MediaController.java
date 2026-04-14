package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.service.PreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/media")
@Tag(name = "Media", description = "缩略图与视频代理接口")
public class MediaController {
    private final PreviewService previewService;

    public MediaController(PreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/thumb/{previewKey}.jpg")
    @Operation(
            summary = "获取缩略图代理",
            description = "按 PreviewKey 下载并缓存缩略图，返回 JPEG 资源。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "成功返回缩略图"),
                    @ApiResponse(responseCode = "404", description = "元数据不存在或已过期", content = @Content(schema = @Schema(hidden = true)))
            }
    )
    public ResponseEntity<Resource> thumbnail(
            @Parameter(description = "预览资源的 opaque 标识，不暴露平台内部 ID")
            @PathVariable String previewKey
    ) {
        Path path = previewService.ensureThumbnail(previewKey);
        FileSystemResource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(resource);
    }

    @GetMapping("/video/{previewKey}.mp4")
    @Operation(
            summary = "获取视频代理",
            description = "当前版本仅保留路由占位，固定返回 501 Not Implemented。",
            responses = {
                    @ApiResponse(responseCode = "501", description = "当前版本未实现视频代理")
            }
    )
    public ResponseEntity<String> video(
            @Parameter(description = "预览资源的 opaque 标识，不暴露平台内部 ID")
            @PathVariable String previewKey
    ) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Video proxy is not implemented in this release.");
    }
}
