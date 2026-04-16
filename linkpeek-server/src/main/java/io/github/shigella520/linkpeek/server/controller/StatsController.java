package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.stats.model.DashboardRange;
import io.github.shigella520.linkpeek.server.stats.model.DashboardStatsResponse;
import io.github.shigella520.linkpeek.server.stats.service.DashboardStatsService;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsMaintenanceService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "Stats", description = "运营统计与看板接口")
public class StatsController {
    private final DashboardStatsService dashboardStatsService;
    private final StatisticsMaintenanceService statisticsMaintenanceService;
    private final LinkPeekProperties properties;

    public StatsController(
            DashboardStatsService dashboardStatsService,
            StatisticsMaintenanceService statisticsMaintenanceService,
            LinkPeekProperties properties
    ) {
        this.dashboardStatsService = dashboardStatsService;
        this.statisticsMaintenanceService = statisticsMaintenanceService;
        this.properties = properties;
    }

    @GetMapping("/api/stats/dashboard")
    @Operation(
            summary = "获取统计看板数据",
            description = "返回看板所需的聚合指标、趋势、热力图和热门链接数据。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "统计数据返回成功"),
                    @ApiResponse(responseCode = "400", description = "时间窗参数非法", content = @Content(schema = @Schema(hidden = true)))
            }
    )
    public DashboardStatsResponse dashboardStats(
            @Parameter(description = "统计时间窗，可选 7d、30d、90d、180d。", example = "30d")
            @RequestParam(name = "range", defaultValue = "30d") String range
    ) {
        try {
            return dashboardStatsService.getDashboardStats(DashboardRange.fromValue(range));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/api/stats/admin/purge-all")
    @Hidden
    public StatisticsMaintenanceService.PurgeResult purgeAllStats(
            @RequestParam(name = "password", required = false) String password
    ) {
        String configuredPassword = properties.getStatsAdminPassword();
        if (!StringUtils.hasText(configuredPassword)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stats admin purge is not enabled.");
        }
        if (!configuredPassword.equals(password)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid management password.");
        }
        return statisticsMaintenanceService.purgeAllData();
    }
}
