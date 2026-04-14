package io.github.shigella520.linkpeek.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Health", description = "服务健康检查接口")
public class HealthController {
    @GetMapping("/api/health")
    @Operation(summary = "服务探活", description = "返回轻量 JSON，用于本地或外部探活。")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
