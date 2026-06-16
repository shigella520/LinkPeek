package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPromptMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.service.AdminAuthService;
import io.github.shigella520.linkpeek.server.admin.service.AdminPreviewEventService;
import io.github.shigella520.linkpeek.server.admin.service.AiTitleConfigService;
import io.github.shigella520.linkpeek.server.admin.service.ProviderConfigService;
import io.github.shigella520.linkpeek.server.admin.service.ServiceLogService;
import io.github.shigella520.linkpeek.server.ai.AiApiKind;
import io.github.shigella520.linkpeek.server.ai.AiProviderDowngradeService;
import io.github.shigella520.linkpeek.server.ai.OpenAiCompatibleTextClient;
import io.github.shigella520.linkpeek.server.ai.AiTitlePrompt;
import io.github.shigella520.linkpeek.server.ai.AiTitleService;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsMaintenanceService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
@Hidden
public class AdminController {
    private static final Pattern STYLE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final AiTitlePrompt AI_PROVIDER_TEST_PROMPT = new AiTitlePrompt(
            "请只返回一行中文标题文本，不要解释、不要 JSON、不要 Markdown、不要引号、不要换行。",
            "AI Provider 连通性测试",
            "LinkPeek AI Provider 测试成功。"
    );
    private static final int AI_PROVIDER_SORT_STEP = 100;
    private static final int MIN_AI_REQUEST_TIMEOUT_SECONDS = 1;
    private static final int MAX_AI_REQUEST_TIMEOUT_SECONDS = 600;

    private final AdminAuthService adminAuthService;
    private final AdminPromptMapper adminPromptMapper;
    private final AiTitleConfigService aiTitleConfigService;
    private final ProviderConfigService providerConfigService;
    private final AiProviderMapper aiProviderMapper;
    private final AiProviderDowngradeService aiProviderDowngradeService;
    private final OpenAiCompatibleTextClient textClient;
    private final ServiceLogService serviceLogService;
    private final StatisticsMaintenanceService statisticsMaintenanceService;
    private final AdminPreviewEventService adminPreviewEventService;
    private final Clock clock;

    public AdminController(
            AdminAuthService adminAuthService,
            AdminPromptMapper adminPromptMapper,
            AiTitleConfigService aiTitleConfigService,
            ProviderConfigService providerConfigService,
            AiProviderMapper aiProviderMapper,
            AiProviderDowngradeService aiProviderDowngradeService,
            OpenAiCompatibleTextClient textClient,
            ServiceLogService serviceLogService,
            StatisticsMaintenanceService statisticsMaintenanceService,
            AdminPreviewEventService adminPreviewEventService,
            Clock clock
    ) {
        this.adminAuthService = adminAuthService;
        this.adminPromptMapper = adminPromptMapper;
        this.aiTitleConfigService = aiTitleConfigService;
        this.providerConfigService = providerConfigService;
        this.aiProviderMapper = aiProviderMapper;
        this.aiProviderDowngradeService = aiProviderDowngradeService;
        this.textClient = textClient;
        this.serviceLogService = serviceLogService;
        this.statisticsMaintenanceService = statisticsMaintenanceService;
        this.adminPreviewEventService = adminPreviewEventService;
        this.clock = clock;
    }

    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest request) {
        return new SessionResponse(adminAuthService.isEnabled(), adminAuthService.isAuthenticated(request));
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest request) {
        AdminAuthService.LoginSession session = adminAuthService.login(request == null ? null : request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, session.cookieHeader())
                .body(new SessionResponse(true, true));
    }

    @PostMapping("/logout")
    public ResponseEntity<SessionResponse> logout(HttpServletRequest request) {
        adminAuthService.logout(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, adminAuthService.logoutCookieHeader())
                .body(new SessionResponse(adminAuthService.isEnabled(), false));
    }

    @PostMapping("/stats/purge-all")
    public StatisticsMaintenanceService.PurgeResult purgeAllStats(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return statisticsMaintenanceService.purgeAllData();
    }

    @GetMapping("/preview-events")
    public AdminPreviewEventService.PreviewEventPage previewEvents(
            HttpServletRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "q", required = false) String query
    ) {
        adminAuthService.requireAuthenticated(request);
        return adminPreviewEventService.previewEvents(page, size, query);
    }

    @DeleteMapping("/preview-events/{previewKey}/cache")
    public AdminPreviewEventService.CacheClearResponse clearPreviewCache(
            HttpServletRequest request,
            @PathVariable String previewKey
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return adminPreviewEventService.clearCache(previewKey);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/logs")
    public ServiceLogService.ServiceLogResponse logs(
            HttpServletRequest request,
            @RequestParam(required = false) Integer lines,
            @RequestParam(required = false) String level,
            @RequestParam(name = "q", required = false) String query
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return serviceLogService.readLogs(lines, level, query);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/prompts")
    public List<AdminPromptRecord> prompts(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return adminPromptMapper.selectAllPrompts();
    }

    @PutMapping("/prompts/{style}")
    @Transactional
    public AdminPromptRecord savePrompt(
            HttpServletRequest request,
            @PathVariable String style,
            @RequestBody PromptRequest promptRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        String normalizedStyle = normalizeWritableStyle(style);
        if (promptRequest == null || !StringUtils.hasText(promptRequest.prompt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt must not be blank.");
        }
        AdminPromptRecord record = new AdminPromptRecord();
        record.setStyle(normalizedStyle);
        record.setPrompt(promptRequest.prompt().strip());
        record.setUpdatedAt(now());
        adminPromptMapper.upsertPrompt(record);
        return adminPromptMapper.selectPrompt(normalizedStyle);
    }

    @DeleteMapping("/prompts/{style}")
    public DeleteResponse deletePrompt(HttpServletRequest request, @PathVariable String style) {
        adminAuthService.requireAuthenticated(request);
        return new DeleteResponse(adminPromptMapper.deletePrompt(normalizeStyle(style)));
    }

    @GetMapping("/ai-title-config")
    public AiTitleConfigService.AiTitleConfigResponse aiTitleConfig(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return aiTitleConfigService.config();
    }

    @PutMapping("/ai-title-config")
    public AiTitleConfigService.AiTitleConfigResponse saveAiTitleConfig(
            HttpServletRequest request,
            @RequestBody AiTitleConfigRequest configRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (configRequest == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI title config payload is required.");
        }
        return aiTitleConfigService.saveTitleFormatPrompt(configRequest.titleFormatPrompt());
    }

    @GetMapping("/provider-config")
    public ProviderConfigResponse providerConfig(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return new ProviderConfigResponse(providerConfigService.allProviderConfigs());
    }

    @PutMapping("/provider-config/{providerId}")
    public ProviderConfigResponse saveProviderConfig(
            HttpServletRequest request,
            @PathVariable String providerId,
            @RequestBody ProviderConfigRequest configRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (configRequest == null || configRequest.values() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider config values are required.");
        }
        providerConfigService.saveProviderConfigs(providerId, configRequest.values());
        return new ProviderConfigResponse(providerConfigService.allProviderConfigs());
    }

    @GetMapping("/ai-providers")
    public List<AiProviderRecord> aiProviders(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return aiProviderMapper.selectAllProviders();
    }

    @GetMapping("/ai-provider-downgrade-config")
    public AiProviderDowngradeService.ConfigResponse aiProviderDowngradeConfig(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return aiProviderDowngradeService.config();
    }

    @PutMapping("/ai-provider-downgrade-config")
    public AiProviderDowngradeService.ConfigResponse saveAiProviderDowngradeConfig(
            HttpServletRequest request,
            @RequestBody AiProviderDowngradeRequest downgradeRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (downgradeRequest == null || downgradeRequest.autoDowngradeEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Auto downgrade enabled value is required.");
        }
        try {
            return aiProviderDowngradeService.saveConfig(
                    downgradeRequest.autoDowngradeEnabled(),
                    downgradeRequest.autoDowngradeFailureThreshold(),
                    downgradeRequest.shareSummaryTimeoutMultiplier()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/ai-providers")
    @Transactional
    public AiProviderRecord createAiProvider(
            HttpServletRequest request,
            @RequestBody AiProviderRequest providerRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        AiProviderRecord record = normalizeAiProvider(null, providerRequest, null);
        aiProviderMapper.insertProvider(record);
        return aiProviderMapper.selectProvider(record.getId());
    }

    @PutMapping("/ai-providers/{id}")
    @Transactional
    public AiProviderRecord updateAiProvider(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody AiProviderRequest providerRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        AiProviderRecord existing = aiProviderMapper.selectProvider(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI provider was not found.");
        }
        AiProviderRecord record = normalizeAiProvider(id, providerRequest, existing);
        aiProviderMapper.updateProvider(record);
        return aiProviderMapper.selectProvider(id);
    }

    @PutMapping("/ai-providers/{id}/enabled")
    public AiProviderRecord updateAiProviderEnabled(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody AiProviderEnabledRequest enabledRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (enabledRequest == null || enabledRequest.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enabled value is required.");
        }
        int updated = aiProviderMapper.updateProviderEnabled(id, enabledRequest.enabled(), now());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI provider was not found.");
        }
        return aiProviderMapper.selectProvider(id);
    }

    @PutMapping("/ai-providers/reorder")
    @Transactional
    public List<AiProviderRecord> reorderAiProviders(
            HttpServletRequest request,
            @RequestBody AiProviderReorderRequest reorderRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (reorderRequest == null || reorderRequest.ids() == null || reorderRequest.ids().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI provider order is required.");
        }

        List<AiProviderRecord> providers = aiProviderMapper.selectAllProviders();
        Set<Long> existingIds = new HashSet<>(providers.stream()
                .map(AiProviderRecord::getId)
                .toList());
        Set<Long> requestedIds = new HashSet<>(reorderRequest.ids());
        if (reorderRequest.ids().size() != requestedIds.size() || !existingIds.equals(requestedIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI provider order payload is stale.");
        }

        long updatedAt = now();
        int sortOrder = AI_PROVIDER_SORT_STEP;
        for (Long providerId : reorderRequest.ids()) {
            aiProviderMapper.updateProviderSortOrder(providerId, sortOrder, updatedAt);
            sortOrder += AI_PROVIDER_SORT_STEP;
        }
        return aiProviderMapper.selectAllProviders();
    }

    @DeleteMapping("/ai-providers/{id}")
    public DeleteResponse deleteAiProvider(HttpServletRequest request, @PathVariable long id) {
        adminAuthService.requireAuthenticated(request);
        return new DeleteResponse(aiProviderMapper.deleteProvider(id));
    }

    @PostMapping("/ai-providers/{id}/test")
    public AiProviderTestResponse testAiProvider(HttpServletRequest request, @PathVariable long id) {
        adminAuthService.requireAuthenticated(request);
        AiProviderRecord provider = aiProviderMapper.selectProvider(id);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI provider was not found.");
        }

        long startedAt = System.nanoTime();
        try {
            Optional<String> output = textClient.generateTitle(provider, AI_PROVIDER_TEST_PROMPT)
                    .map(String::strip)
                    .filter(StringUtils::hasText);
            long durationMs = elapsedMillis(startedAt);
            if (output.isPresent()) {
                return new AiProviderTestResponse(true, "测试成功。", output.get(), durationMs);
            }
            return new AiProviderTestResponse(false, "AI 服务返回空内容。", "", durationMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AiProviderTestResponse(false, "测试中断。", "", elapsedMillis(startedAt));
        } catch (IOException | RuntimeException exception) {
            return new AiProviderTestResponse(false, exception.getMessage(), "", elapsedMillis(startedAt));
        }
    }

    private AiProviderRecord normalizeAiProvider(Long id, AiProviderRequest request, AiProviderRecord existing) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI provider payload is required.");
        }
        String name = required(request.name(), "name");
        String inputBaseUrl = required(request.baseUrl(), "BaseURL");
        String model = required(request.model(), "model");
        AiApiKind apiKind;
        String baseUrl;
        try {
            apiKind = AiApiKind.fromValue(request.apiKind());
            baseUrl = AiApiKind.normalizeBaseUrl(inputBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }

        AiProviderRecord record = new AiProviderRecord();
        record.setId(id);
        record.setName(name);
        record.setEnabled(request.enabled() == null ? existing == null || existing.isEnabled() : request.enabled());
        record.setSortOrder(request.sortOrder() == null ? defaultSortOrder(existing) : request.sortOrder());
        record.setBaseUrl(baseUrl);
        record.setApiKind(apiKind.name());
        record.setModel(model);
        record.setEffort(StringUtils.hasText(request.effort()) ? request.effort().strip() : null);
        record.setRequestTimeoutSeconds(normalizeAiRequestTimeoutSeconds(request.requestTimeoutSeconds(), existing));
        record.setApiKey(request.apiKey() == null ? "" : request.apiKey().strip());
        record.setUpdatedAt(now());
        return record;
    }

    private int defaultSortOrder(AiProviderRecord existing) {
        if (existing != null) {
            return existing.getSortOrder();
        }
        return aiProviderMapper.selectAllProviders()
                .stream()
                .mapToInt(AiProviderRecord::getSortOrder)
                .max()
                .orElse(0) + AI_PROVIDER_SORT_STEP;
    }

    private int normalizeAiRequestTimeoutSeconds(Integer requestTimeoutSeconds, AiProviderRecord existing) {
        int timeoutSeconds = requestTimeoutSeconds == null
                ? (existing == null ? OpenAiCompatibleTextClient.DEFAULT_REQUEST_TIMEOUT_SECONDS : existing.getRequestTimeoutSeconds())
                : requestTimeoutSeconds;
        if (timeoutSeconds < MIN_AI_REQUEST_TIMEOUT_SECONDS || timeoutSeconds > MAX_AI_REQUEST_TIMEOUT_SECONDS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "AI request timeout must be between 1 and 600 seconds."
            );
        }
        return timeoutSeconds;
    }

    private String normalizeStyle(String style) {
        if (!StringUtils.hasText(style) || !STYLE_PATTERN.matcher(style.strip()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Style must be 1-64 chars and only contain letters, numbers, dot, underscore, or dash.");
        }
        return AiTitleService.normalizeStyleKey(style);
    }

    private String normalizeWritableStyle(String style) {
        String normalizedStyle = normalizeStyle(style);
        if (AiTitleService.isFreestyleStyle(normalizedStyle)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Style key 'FREESTYLE' is reserved for freestyle mode.");
        }
        return normalizedStyle;
    }

    private String required(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must not be blank.");
        }
        return value.strip();
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record SessionResponse(boolean enabled, boolean authenticated) {
    }

    public record LoginRequest(String password) {
    }

    public record PromptRequest(String prompt) {
    }

    public record AiTitleConfigRequest(String titleFormatPrompt) {
    }

    public record ProviderConfigRequest(Map<String, String> values) {
    }

    public record ProviderConfigResponse(Map<String, Map<String, String>> configs) {
    }

    public record AiProviderRequest(
            String name,
            Boolean enabled,
            Integer sortOrder,
            String baseUrl,
            String apiKind,
            String model,
            String effort,
            Integer requestTimeoutSeconds,
            String apiKey
    ) {
    }

    public record AiProviderDowngradeRequest(
            Boolean autoDowngradeEnabled,
            Integer autoDowngradeFailureThreshold,
            Double shareSummaryTimeoutMultiplier
    ) {
    }

    public record AiProviderEnabledRequest(Boolean enabled) {
    }

    public record AiProviderReorderRequest(List<Long> ids) {
    }

    public record AiProviderTestResponse(
            boolean success,
            String message,
            String output,
            long durationMs
    ) {
    }

    public record DeleteResponse(int deleted) {
    }
}
