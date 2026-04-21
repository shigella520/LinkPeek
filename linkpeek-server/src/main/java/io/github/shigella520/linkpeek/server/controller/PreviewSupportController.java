package io.github.shigella520.linkpeek.server.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.shigella520.linkpeek.core.error.InvalidPreviewUrlException;
import io.github.shigella520.linkpeek.server.service.PreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Preview", description = "统一链接预览入口")
public class PreviewSupportController {
    private static final String INVALID_URL = "INVALID_URL";

    private final PreviewService previewService;

    public PreviewSupportController(PreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping(value = "/api/preview/support", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "判断链接是否支持预览",
            description = "仅基于已注册 provider 的 supports 规则做轻量 URL 形态判定，不抓取上游元数据。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "URL 判定完成"),
                    @ApiResponse(responseCode = "400", description = "URL 参数非法")
            }
    )
    public ResponseEntity<SupportResponse> support(
            @Parameter(
                    description = "完整目标 URL，必须为已 URL 编码的 http/https 地址。",
                    example = "https://www.bilibili.com/video/BV1xx411c7mD"
            )
            @RequestParam(value = "url", required = false) String url
    ) {
        try {
            return response(HttpStatus.OK, new SupportResponse(previewService.supports(url), null, null));
        } catch (InvalidPreviewUrlException exception) {
            return response(HttpStatus.BAD_REQUEST, new SupportResponse(false, INVALID_URL, exception.getMessage()));
        }
    }

    private ResponseEntity<SupportResponse> response(HttpStatus status, SupportResponse body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupportResponse(
            boolean supported,
            String errorCode,
            String message
    ) {
    }
}
