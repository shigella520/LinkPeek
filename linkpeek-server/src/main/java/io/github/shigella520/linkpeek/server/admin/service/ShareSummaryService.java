package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryLinkRow;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodType;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodSelectionMode;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunStatus;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTriggerType;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryLinkMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.ai.AiProviderDowngradeService;
import io.github.shigella520.linkpeek.server.ai.AiTextPrompt;
import io.github.shigella520.linkpeek.server.ai.OpenAiCompatibleTextClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ShareSummaryService {
    private static final Logger log = LoggerFactory.getLogger(ShareSummaryService.class);
    private static final int DEFAULT_MAX_LINKS = 100;
    private static final int MIN_MAX_LINKS = 1;
    private static final int MAX_MAX_LINKS = 2_000;
    private static final int DEFAULT_MIN_LINKS = 1;
    private static final int MIN_MIN_LINKS = 1;
    private static final int MAX_MIN_LINKS = 2_000;
    private static final int MAX_SHARE_SUMMARY_REQUEST_TIMEOUT_SECONDS = 3_600;
    private static final int CATCH_UP_LIMIT = 7;
    private static final long RUNNING_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final String DEFAULT_SUMMARY_INSTRUCTIONS = "请根据用户提供的分享总结提示词和链接分享列表，生成一份结构清晰、信息密度高的中文分享总结。";
    private static final String OPERATION_SHARE_SUMMARY = "SHARE_SUMMARY";
    private static final DateTimeFormatter SUMMARY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ShareSummaryMapper shareSummaryMapper;
    private final ShareSummaryLinkMapper shareSummaryLinkMapper;
    private final AiProviderMapper aiProviderMapper;
    private final OpenAiCompatibleTextClient textClient;
    private final ShareSummaryImageService shareSummaryImageService;
    private final ShareSummaryAudioService shareSummaryAudioService;
    private final AiProviderDowngradeService aiProviderDowngradeService;
    private final Clock clock;
    private final AtomicBoolean scheduledRunning = new AtomicBoolean(false);

    public ShareSummaryService(
            ShareSummaryMapper shareSummaryMapper,
            ShareSummaryLinkMapper shareSummaryLinkMapper,
            AiProviderMapper aiProviderMapper,
            OpenAiCompatibleTextClient textClient,
            ShareSummaryImageService shareSummaryImageService,
            ShareSummaryAudioService shareSummaryAudioService,
            AiProviderDowngradeService aiProviderDowngradeService,
            Clock clock
    ) {
        this.shareSummaryMapper = shareSummaryMapper;
        this.shareSummaryLinkMapper = shareSummaryLinkMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.textClient = textClient;
        this.shareSummaryImageService = shareSummaryImageService;
        this.shareSummaryAudioService = shareSummaryAudioService;
        this.aiProviderDowngradeService = aiProviderDowngradeService;
        this.clock = clock;
    }

    public List<ShareSummaryTaskRecord> tasks() {
        return shareSummaryMapper.selectTasks();
    }

    public ShareSummaryTaskRecord createTask(TaskRequest request) {
        ShareSummaryTaskRecord task = normalizeTask(null, request);
        long now = now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeleted(false);
        shareSummaryMapper.insertTask(task);
        return shareSummaryMapper.selectTask(task.getId());
    }

    public ShareSummaryTaskRecord updateTask(long taskId, TaskRequest request) {
        ShareSummaryTaskRecord existing = existingTask(taskId);
        ShareSummaryTaskRecord task = normalizeTask(existing, request);
        task.setId(taskId);
        task.setCreatedAt(existing.getCreatedAt());
        task.setUpdatedAt(now());
        task.setDeleted(false);
        if (shareSummaryMapper.updateTask(task) == 0) {
            throw new IllegalArgumentException("Share summary task was not found.");
        }
        return shareSummaryMapper.selectTask(taskId);
    }

    public DeleteResponse deleteTask(long taskId) {
        return new DeleteResponse(shareSummaryMapper.deleteTask(taskId, now()));
    }

    public ShareSummaryRunRecord runTask(long taskId) {
        ShareSummaryTaskRecord task = existingTask(taskId);
        Window window = manualWindow(task);
        return executeWindow(task, window, ShareSummaryTriggerType.MANUAL);
    }

    public RunPage runs(Integer page, Integer size, Long taskId, String status, String triggerType) {
        int normalizedSize = normalizePageSize(size);
        int normalizedPage = page == null || page < 1 ? 1 : page;
        String normalizedStatus = normalizeStatusFilter(status);
        String normalizedTriggerType = normalizeTriggerTypeFilter(triggerType);
        long total = shareSummaryMapper.countRuns(taskId, normalizedStatus, normalizedTriggerType);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalizedSize);
        int offset = (normalizedPage - 1) * normalizedSize;
        List<ShareSummaryRunRecord> items = shareSummaryMapper.selectRuns(
                        taskId,
                        normalizedStatus,
                        normalizedTriggerType,
                        normalizedSize,
                        offset
                ).stream()
                .map(this::withShareAssetSummaries)
                .toList();
        return new RunPage(items, normalizedPage, normalizedSize, total, totalPages);
    }

    public ShareSummaryRunRecord run(long runId) {
        ShareSummaryRunRecord run = shareSummaryMapper.selectRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Share summary run was not found.");
        }
        return withShareAssetSummaries(run);
    }

    @Transactional
    public DeleteRunResponse deleteRun(long runId) {
        ShareSummaryRunRecord run = shareSummaryMapper.selectRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Share summary run was not found.");
        }
        if (ShareSummaryRunStatus.RUNNING.name().equals(run.getStatus())) {
            throw new IllegalStateException("Share summary run is in progress.");
        }
        int deletedAudios = shareSummaryAudioService == null ? 0 : shareSummaryAudioService.deleteAudiosForRun(runId);
        int deletedImages = shareSummaryImageService == null ? 0 : shareSummaryImageService.deleteImagesForRun(runId);
        int deletedRuns = shareSummaryMapper.deleteRun(runId);
        return new DeleteRunResponse(deletedRuns, deletedImages, deletedAudios);
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void scheduledScan() {
        if (!scheduledRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            runScheduledScan();
        } finally {
            scheduledRunning.set(false);
        }
    }

    void runScheduledScan() {
        long now = now();
        shareSummaryMapper.markStaleRunningRunsFailed(now - RUNNING_TIMEOUT_MILLIS, now);
        if (shareSummaryImageService != null) {
            shareSummaryImageService.markStaleActiveImagesFailed();
        }
        if (shareSummaryAudioService != null) {
            shareSummaryAudioService.markStaleActiveAudiosFailed();
        }
        for (ShareSummaryTaskRecord task : shareSummaryMapper.selectEnabledTasks()) {
            try {
                runDueWindows(task);
            } catch (RuntimeException exception) {
                log.warn("share_summary_scheduled_task_failed taskId={} message={}", task.getId(), exception.getMessage(), exception);
            }
        }
    }

    private void runDueWindows(ShareSummaryTaskRecord task) {
        List<Window> windows = dueWindows(task);
        for (Window window : windows) {
            ShareSummaryRunRecord existing = shareSummaryMapper.selectScheduledRunForWindow(
                    task.getId(),
                    window.startMillis(),
                    window.endMillis()
            );
            if (existing != null && (ShareSummaryRunStatus.SUCCESS.name().equals(existing.getStatus())
                    || ShareSummaryRunStatus.EMPTY.name().equals(existing.getStatus())
                    || ShareSummaryRunStatus.RUNNING.name().equals(existing.getStatus()))) {
                continue;
            }
            executeWindow(task, window, ShareSummaryTriggerType.SCHEDULED);
        }
    }

    List<Window> dueWindows(ShareSummaryTaskRecord task) {
        ShareSummaryPeriodType periodType = ShareSummaryPeriodType.fromValue(task.getPeriodType());
        ShareSummaryPeriodSelectionMode selectionMode = ShareSummaryPeriodSelectionMode.fromValue(task.getPeriodSelectionMode());
        ZoneId zone = clock.getZone();
        LocalDateTime nowDateTime = LocalDateTime.ofInstant(Instant.now(clock), zone);
        LocalTime runTime = parseRunTime(task.getRunTime());
        LocalDateTime latestDueTrigger = latestDueTrigger(periodType, selectionMode, nowDateTime, runTime, task);

        ShareSummaryRunRecord latest = shareSummaryMapper.selectLatestCompletedScheduledRun(task.getId());
        LocalDateTime nextTrigger = latest == null
                ? latestDueTrigger
                : nextTriggerAfter(periodType, selectionMode, triggerFromWindowEnd(periodType, selectionMode, latest.getWindowEnd(), zone, runTime, task), runTime, task);
        List<Window> windows = new ArrayList<>();
        while (!nextTrigger.isAfter(latestDueTrigger)) {
            windows.add(windowForTrigger(periodType, selectionMode, nextTrigger, zone));
            if (windows.size() >= CATCH_UP_LIMIT) {
                break;
            }
            nextTrigger = nextTriggerAfter(periodType, selectionMode, nextTrigger, runTime, task);
        }
        return windows;
    }

    private LocalDateTime latestDueTrigger(
            ShareSummaryPeriodType periodType,
            ShareSummaryPeriodSelectionMode selectionMode,
            LocalDateTime nowDateTime,
            LocalTime runTime,
            ShareSummaryTaskRecord task
    ) {
        return switch (periodType) {
            case DAILY -> latestDailyTrigger(nowDateTime, runTime);
            case WEEKLY -> latestWeeklyTrigger(nowDateTime, runTime, task.getDayOfWeek());
            case MONTHLY -> latestMonthlyTrigger(selectionMode, nowDateTime, runTime);
        };
    }

    private LocalDateTime latestDailyTrigger(LocalDateTime nowDateTime, LocalTime runTime) {
        LocalDateTime trigger = nowDateTime.toLocalDate().atTime(runTime);
        return trigger.isAfter(nowDateTime) ? trigger.minusDays(1) : trigger;
    }

    private LocalDateTime latestWeeklyTrigger(LocalDateTime nowDateTime, LocalTime runTime, Integer dayOfWeek) {
        DayOfWeek configuredDay = DayOfWeek.of(dayOfWeek);
        LocalDate scheduledDate = nowDateTime.toLocalDate().with(TemporalAdjusters.previousOrSame(configuredDay));
        LocalDateTime trigger = scheduledDate.atTime(runTime);
        return trigger.isAfter(nowDateTime) ? trigger.minusWeeks(1) : trigger;
    }

    private LocalDateTime latestMonthlyTrigger(
            ShareSummaryPeriodSelectionMode selectionMode,
            LocalDateTime nowDateTime,
            LocalTime runTime
    ) {
        YearMonth currentMonth = YearMonth.from(nowDateTime);
        LocalDateTime trigger = monthlyTrigger(selectionMode, currentMonth, runTime);
        if (trigger.isAfter(nowDateTime)) {
            trigger = monthlyTrigger(selectionMode, currentMonth.minusMonths(1), runTime);
        }
        return trigger;
    }

    private LocalDateTime nextTriggerAfter(
            ShareSummaryPeriodType periodType,
            ShareSummaryPeriodSelectionMode selectionMode,
            LocalDateTime after,
            LocalTime runTime,
            ShareSummaryTaskRecord task
    ) {
        return switch (periodType) {
            case DAILY -> nextDailyTriggerAfter(after, runTime);
            case WEEKLY -> nextWeeklyTriggerAfter(after, runTime, task.getDayOfWeek());
            case MONTHLY -> nextMonthlyTriggerAfter(selectionMode, after, runTime);
        };
    }

    private LocalDateTime triggerFromWindowEnd(
            ShareSummaryPeriodType periodType,
            ShareSummaryPeriodSelectionMode selectionMode,
            long windowEnd,
            ZoneId zone,
            LocalTime runTime,
            ShareSummaryTaskRecord task
    ) {
        LocalDateTime windowEndDateTime = millisToDateTime(windowEnd, zone);
        if (selectionMode == ShareSummaryPeriodSelectionMode.CURRENT) {
            return windowEndDateTime;
        }
        return switch (periodType) {
            case DAILY -> windowEndDateTime.toLocalDate().atTime(runTime);
            case WEEKLY -> windowEndDateTime.toLocalDate()
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(task.getDayOfWeek())))
                    .atTime(runTime);
            case MONTHLY -> YearMonth.from(windowEndDateTime).atDay(1).atTime(runTime);
        };
    }

    private LocalDateTime nextDailyTriggerAfter(LocalDateTime after, LocalTime runTime) {
        LocalDateTime trigger = after.toLocalDate().atTime(runTime);
        return trigger.isAfter(after) ? trigger : trigger.plusDays(1);
    }

    private LocalDateTime nextWeeklyTriggerAfter(LocalDateTime after, LocalTime runTime, Integer dayOfWeek) {
        DayOfWeek configuredDay = DayOfWeek.of(dayOfWeek);
        LocalDate scheduledDate = after.toLocalDate().with(TemporalAdjusters.nextOrSame(configuredDay));
        LocalDateTime trigger = scheduledDate.atTime(runTime);
        return trigger.isAfter(after) ? trigger : trigger.plusWeeks(1);
    }

    private LocalDateTime nextMonthlyTriggerAfter(
            ShareSummaryPeriodSelectionMode selectionMode,
            LocalDateTime after,
            LocalTime runTime
    ) {
        YearMonth month = YearMonth.from(after);
        LocalDateTime trigger = monthlyTrigger(selectionMode, month, runTime);
        if (!trigger.isAfter(after)) {
            trigger = monthlyTrigger(selectionMode, month.plusMonths(1), runTime);
        }
        return trigger;
    }

    private LocalDateTime monthlyTrigger(
            ShareSummaryPeriodSelectionMode selectionMode,
            YearMonth month,
            LocalTime runTime
    ) {
        return switch (selectionMode) {
            case CURRENT -> month.atEndOfMonth().atTime(runTime);
            case PREVIOUS -> month.atDay(1).atTime(runTime);
        };
    }

    private Window windowForTrigger(
            ShareSummaryPeriodType periodType,
            ShareSummaryPeriodSelectionMode selectionMode,
            LocalDateTime trigger,
            ZoneId zone
    ) {
        LocalDate windowStart = switch (selectionMode) {
            case CURRENT -> currentPeriodWindowStart(periodType, trigger);
            case PREVIOUS -> previousPeriodWindowStart(periodType, trigger);
        };
        LocalDateTime windowEnd = switch (selectionMode) {
            case CURRENT -> trigger;
            case PREVIOUS -> previousPeriodWindowEnd(periodType, trigger);
        };
        return new Window(
                windowStart.atStartOfDay(zone).toInstant().toEpochMilli(),
                windowEnd.atZone(zone).toInstant().toEpochMilli()
        );
    }

    private LocalDate currentPeriodWindowStart(ShareSummaryPeriodType periodType, LocalDateTime trigger) {
        return switch (periodType) {
            case DAILY -> trigger.toLocalDate();
            case WEEKLY -> trigger.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> trigger.toLocalDate().withDayOfMonth(1);
        };
    }

    private LocalDate previousPeriodWindowStart(ShareSummaryPeriodType periodType, LocalDateTime trigger) {
        return switch (periodType) {
            case DAILY -> trigger.toLocalDate().minusDays(1);
            case WEEKLY -> trigger.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .minusWeeks(1);
            case MONTHLY -> YearMonth.from(trigger).minusMonths(1).atDay(1);
        };
    }

    private LocalDateTime previousPeriodWindowEnd(ShareSummaryPeriodType periodType, LocalDateTime trigger) {
        LocalDate endDate = switch (periodType) {
            case DAILY -> trigger.toLocalDate();
            case WEEKLY -> trigger.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> YearMonth.from(trigger).atDay(1);
        };
        return endDate.atStartOfDay();
    }

    private LocalDateTime millisToDateTime(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime();
    }

    private ShareSummaryRunRecord executeWindow(
            ShareSummaryTaskRecord task,
            Window window,
            ShareSummaryTriggerType triggerType
    ) {
        ShareSummaryRunRecord run = createRunningRun(task, window, triggerType);
        shareSummaryMapper.insertRun(run);
        try {
            executeRun(run, task, window);
        } catch (RuntimeException exception) {
            run.setStatus(ShareSummaryRunStatus.FAILED.name());
            run.setErrorMessage(limitError(exception.getMessage()));
            run.setFinishedAt(now());
            shareSummaryMapper.updateRun(run);
        }
        ShareSummaryRunRecord savedRun = shareSummaryMapper.selectRun(run.getId());
        if (shareSummaryImageService != null && ShareSummaryRunStatus.SUCCESS.name().equals(savedRun.getStatus())) {
            shareSummaryImageService.triggerAutoGeneration(savedRun);
        }
        if (shareSummaryAudioService != null && ShareSummaryRunStatus.SUCCESS.name().equals(savedRun.getStatus())) {
            shareSummaryAudioService.triggerAutoGeneration(savedRun);
        }
        return withShareAssetSummaries(savedRun);
    }

    private ShareSummaryRunRecord createRunningRun(
            ShareSummaryTaskRecord task,
            Window window,
            ShareSummaryTriggerType triggerType
    ) {
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setTaskId(task.getId());
        run.setTaskName(task.getName());
        run.setTriggerType(triggerType.name());
        run.setPeriodType(task.getPeriodType());
        run.setWindowStart(window.startMillis());
        run.setWindowEnd(window.endMillis());
        run.setStatus(ShareSummaryRunStatus.RUNNING.name());
        run.setPromptSnapshot(task.getPrompt());
        run.setStartedAt(now());
        return run;
    }

    private void executeRun(ShareSummaryRunRecord run, ShareSummaryTaskRecord task, Window window) {
        int linkCount = shareSummaryLinkMapper.countCreatedEvents(window.startMillis(), window.endMillis());
        List<ShareSummaryLinkRow> summaryLinks = shareSummaryLinkMapper.selectSummaryLinks(window.startMillis(), window.endMillis());
        int uniqueCount = summaryLinks.size();
        List<ShareSummaryLinkRow> inputLinks = summaryLinks.stream()
                .limit(task.getMaxLinks())
                .toList();
        run.setLinkCount(linkCount);
        run.setUniqueLinkCount(uniqueCount);
        run.setInputLinkCount(inputLinks.size());
        if (inputLinks.isEmpty()) {
            run.setStatus(ShareSummaryRunStatus.EMPTY.name());
            run.setReport("");
            run.setErrorMessage("No link titles were found in the summary window.");
            run.setFinishedAt(now());
            shareSummaryMapper.updateRun(run);
            return;
        }
        if (uniqueCount < task.getMinLinks()) {
            run.setStatus(ShareSummaryRunStatus.EMPTY.name());
            run.setReport("");
            run.setErrorMessage("Link title count %d is below the configured minimum %d.".formatted(uniqueCount, task.getMinLinks()));
            run.setFinishedAt(now());
            shareSummaryMapper.updateRun(run);
            return;
        }

        AiSummaryResult result = requestAiSummary(task, window, inputLinks);
        run.setStatus(ShareSummaryRunStatus.SUCCESS.name());
        run.setAiProviderNames(String.join("/", result.providerNames()));
        run.setAiDurationMs(result.durationMs());
        run.setReport(result.report());
        run.setFinishedAt(now());
        shareSummaryMapper.updateRun(run);
    }

    private AiSummaryResult requestAiSummary(
            ShareSummaryTaskRecord task,
            Window window,
            List<ShareSummaryLinkRow> links
    ) {
        List<AiProviderRecord> providers = aiProviderMapper.selectEnabledProviders();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No enabled AI provider is configured.");
        }

        AiTextPrompt prompt = new AiTextPrompt(
                DEFAULT_SUMMARY_INSTRUCTIONS,
                "分享总结提示词：\n" + task.getPrompt(),
                summaryContent(window, links)
        );
        List<String> providerNames = new ArrayList<>();
        long durationMs = 0;
        String lastError = "";
        double shareSummaryTimeoutMultiplier = shareSummaryTimeoutMultiplier();
        for (AiProviderRecord provider : providers) {
            long startedAt = System.nanoTime();
            try {
                OpenAiCompatibleTextClient.TextResult result = textClient.generateTextResult(
                        providerWithShareSummaryTimeout(provider, shareSummaryTimeoutMultiplier),
                        prompt
                );
                long attemptDurationMs = result.durationMs() > 0 ? result.durationMs() : elapsedMillis(startedAt);
                durationMs += attemptDurationMs;
                providerNames.add(provider.getName());
                Optional<String> report = result.text()
                        .map(String::strip)
                        .filter(StringUtils::hasText);
                if (report.isPresent()) {
                    recordAiProviderSuccess(provider);
                    return new AiSummaryResult(report.get(), providerNames, durationMs);
                }
                lastError = "AI provider returned empty summary.";
                recordAiProviderFailure(provider, attemptDurationMs, new IllegalStateException(lastError));
            } catch (InterruptedException exception) {
                long attemptDurationMs = elapsedMillis(startedAt);
                durationMs += attemptDurationMs;
                recordAiProviderFailure(provider, attemptDurationMs, exception);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI summary request was interrupted.", exception);
            } catch (IOException | RuntimeException exception) {
                long attemptDurationMs = elapsedMillis(startedAt);
                durationMs += attemptDurationMs;
                providerNames.add(provider.getName());
                lastError = exception.getMessage();
                log.warn(
                        "share_summary_ai_request_failed taskId={} providerId={} providerName={} message={}",
                        task.getId(),
                        provider.getId(),
                        provider.getName(),
                        exception.getMessage(),
                        exception
                );
                recordAiProviderFailure(provider, attemptDurationMs, exception);
            }
        }
        throw new IllegalStateException(StringUtils.hasText(lastError) ? lastError : "AI summary request failed.");
    }

    private double shareSummaryTimeoutMultiplier() {
        return aiProviderDowngradeService == null
                ? AiProviderDowngradeService.DEFAULT_SHARE_SUMMARY_TIMEOUT_MULTIPLIER
                : aiProviderDowngradeService.shareSummaryTimeoutMultiplier();
    }

    private AiProviderRecord providerWithShareSummaryTimeout(AiProviderRecord provider, double multiplier) {
        AiProviderRecord requestProvider = new AiProviderRecord();
        requestProvider.setId(provider.getId());
        requestProvider.setName(provider.getName());
        requestProvider.setEnabled(provider.isEnabled());
        requestProvider.setSortOrder(provider.getSortOrder());
        requestProvider.setBaseUrl(provider.getBaseUrl());
        requestProvider.setApiKind(provider.getApiKind());
        requestProvider.setModel(provider.getModel());
        requestProvider.setEffort(provider.getEffort());
        requestProvider.setApiKey(provider.getApiKey());
        requestProvider.setUpdatedAt(provider.getUpdatedAt());
        requestProvider.setRequestTimeoutSeconds(shareSummaryRequestTimeout(provider, multiplier));
        return requestProvider;
    }

    private int shareSummaryRequestTimeout(AiProviderRecord provider, double multiplier) {
        int baseTimeoutSeconds = provider.getRequestTimeoutSeconds() > 0
                ? provider.getRequestTimeoutSeconds()
                : OpenAiCompatibleTextClient.DEFAULT_REQUEST_TIMEOUT_SECONDS;
        long timeoutSeconds = Math.round(baseTimeoutSeconds * multiplier);
        timeoutSeconds = Math.max(1L, Math.min(MAX_SHARE_SUMMARY_REQUEST_TIMEOUT_SECONDS, timeoutSeconds));
        return (int) timeoutSeconds;
    }

    private void recordAiProviderSuccess(AiProviderRecord provider) {
        if (aiProviderDowngradeService != null) {
            aiProviderDowngradeService.recordSuccess(provider);
        }
    }

    private void recordAiProviderFailure(AiProviderRecord provider, long durationMs, Throwable exception) {
        if (aiProviderDowngradeService != null) {
            aiProviderDowngradeService.recordFailure(provider, OPERATION_SHARE_SUMMARY, durationMs, exception);
        }
    }

    private String summaryContent(Window window, List<ShareSummaryLinkRow> links) {
        StringBuilder content = new StringBuilder();
        content.append("总结窗口：\n")
                .append(window.startMillis())
                .append(" ~ ")
                .append(window.endMillis())
                .append("\n\n链接分享列表：\n");
        for (int index = 0; index < links.size(); index++) {
            ShareSummaryLinkRow link = links.get(index);
            content.append("- 标题：")
                    .append(link.getTitle())
                    .append('\n')
                    .append("  - 链接：")
                    .append(summaryLinkUrl(link))
                    .append('\n')
                    .append("  - 分享时间：")
                    .append(summaryTime(link.getFirstOccurredAt()))
                    .append('\n');
        }
        return content.toString();
    }

    private String summaryLinkUrl(ShareSummaryLinkRow link) {
        return link.getCanonicalUrl() == null ? "" : link.getCanonicalUrl().strip();
    }

    private String summaryTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(clock.getZone())
                .format(SUMMARY_TIME_FORMATTER);
    }

    private ShareSummaryTaskRecord normalizeTask(ShareSummaryTaskRecord existing, TaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Share summary task payload is required.");
        }
        ShareSummaryPeriodType periodType = ShareSummaryPeriodType.fromValue(request.periodType());
        ShareSummaryTaskRecord task = new ShareSummaryTaskRecord();
        task.setName(required(request.name(), "Task name"));
        task.setEnabled(request.enabled() == null ? existing == null || existing.isEnabled() : request.enabled());
        task.setPeriodType(periodType.name());
        task.setPeriodSelectionMode(ShareSummaryPeriodSelectionMode.fromValue(request.periodSelectionMode()).name());
        task.setRunTime(normalizeRunTime(request.runTime()));
        task.setPrompt(required(request.prompt(), "Prompt"));
        task.setMaxLinks(normalizeMaxLinks(request.maxLinks()));
        task.setMinLinks(normalizeMinLinks(request.minLinks()));
        task.setDayOfWeek(periodType == ShareSummaryPeriodType.WEEKLY ? normalizeDayOfWeek(request.dayOfWeek()) : null);
        return task;
    }

    private Window manualWindow(ShareSummaryTaskRecord task) {
        ShareSummaryPeriodType periodType = ShareSummaryPeriodType.fromValue(task.getPeriodType());
        ShareSummaryPeriodSelectionMode selectionMode = ShareSummaryPeriodSelectionMode.fromValue(task.getPeriodSelectionMode());
        ZoneId zone = clock.getZone();
        LocalDateTime nowDateTime = LocalDateTime.ofInstant(Instant.now(clock), zone);
        return windowForTrigger(periodType, selectionMode, nowDateTime, zone);
    }

    private ShareSummaryTaskRecord existingTask(long taskId) {
        ShareSummaryTaskRecord task = shareSummaryMapper.selectTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Share summary task was not found.");
        }
        return task;
    }

    private String normalizeStatusFilter(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        ShareSummaryRunStatus.valueOf(normalized);
        return normalized;
    }

    private String normalizeTriggerTypeFilter(String triggerType) {
        if (!StringUtils.hasText(triggerType)) {
            return null;
        }
        String normalized = triggerType.strip().toUpperCase(Locale.ROOT);
        ShareSummaryTriggerType.valueOf(normalized);
        return normalized;
    }

    private int normalizePageSize(Integer size) {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private String required(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.strip();
    }

    private String normalizeRunTime(String runTime) {
        LocalTime parsed = parseRunTime(runTime);
        return "%02d:%02d".formatted(parsed.getHour(), parsed.getMinute());
    }

    private LocalTime parseRunTime(String runTime) {
        if (!StringUtils.hasText(runTime)) {
            throw new IllegalArgumentException("Run time is required.");
        }
        try {
            return LocalTime.parse(runTime.strip());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Run time must use HH:mm format.", exception);
        }
    }

    private Integer normalizeDayOfWeek(Integer dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("Day of week must be between 1 and 7.");
        }
        return dayOfWeek;
    }

    private int normalizeMaxLinks(Integer maxLinks) {
        int value = maxLinks == null ? DEFAULT_MAX_LINKS : maxLinks;
        if (value < MIN_MAX_LINKS || value > MAX_MAX_LINKS) {
            throw new IllegalArgumentException("Max links must be between 1 and 2000.");
        }
        return value;
    }

    private int normalizeMinLinks(Integer minLinks) {
        int value = minLinks == null ? DEFAULT_MIN_LINKS : minLinks;
        if (value < MIN_MIN_LINKS || value > MAX_MIN_LINKS) {
            throw new IllegalArgumentException("Min links must be between 1 and 2000.");
        }
        return value;
    }

    private String limitError(String message) {
        if (!StringUtils.hasText(message)) {
            return "Share summary execution failed.";
        }
        String stripped = message.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private ShareSummaryRunRecord withShareAssetSummaries(ShareSummaryRunRecord run) {
        if (run == null || run.getId() == null) {
            return run;
        }
        if (shareSummaryImageService != null) {
            ShareSummaryImageService.ImageSummary summary = shareSummaryImageService.imageSummary(run.getId());
            run.setImageStatus(summary.imageStatus());
            run.setLatestImageUrl(summary.latestImageUrl());
            run.setOgImageUrl(summary.ogImageUrl());
            run.setOgPageUrl(summary.ogPageUrl());
            run.setOgShareUrl(summary.ogPageUrl());
            run.setOgTitle(summary.ogTitle());
            run.setOgDescription(summary.ogDescription());
            run.setImageErrorMessage(summary.imageErrorMessage());
        }
        if (shareSummaryAudioService != null) {
            ShareSummaryAudioService.AudioSummary summary = shareSummaryAudioService.audioSummary(run.getId());
            run.setAudioStatus(summary.audioStatus());
            run.setAudioUrl(summary.audioUrl());
            run.setAudioErrorMessage(summary.audioErrorMessage());
        }
        return run;
    }

    public record TaskRequest(
            String name,
            Boolean enabled,
            String periodType,
            String periodSelectionMode,
            String runTime,
            Integer dayOfWeek,
            String prompt,
            Integer maxLinks,
            Integer minLinks
    ) {
    }

    public record RunPage(
            List<ShareSummaryRunRecord> items,
            int page,
            int size,
            long total,
            int totalPages
    ) {
    }

    public record DeleteResponse(int deleted) {
    }

    public record DeleteRunResponse(int deleted, int deletedImages, int deletedAudios) {
    }

    public record Window(long startMillis, long endMillis) {
    }

    private record AiSummaryResult(String report, List<String> providerNames, long durationMs) {
    }
}
