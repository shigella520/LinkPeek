package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryLinkRow;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodType;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunStatus;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTriggerType;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryLinkMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.ai.AiTextPrompt;
import io.github.shigella520.linkpeek.server.ai.AiTitleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
    private static final int MAX_MAX_LINKS = 500;
    private static final int CATCH_UP_LIMIT = 7;
    private static final long RUNNING_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final String DEFAULT_SUMMARY_INSTRUCTIONS = "请根据用户提供的分享总结提示词和链接标题列表，生成一份结构清晰、信息密度高的中文分享总结。";

    private final ShareSummaryMapper shareSummaryMapper;
    private final ShareSummaryLinkMapper shareSummaryLinkMapper;
    private final AiProviderMapper aiProviderMapper;
    private final AiTitleClient aiTitleClient;
    private final Clock clock;
    private final AtomicBoolean scheduledRunning = new AtomicBoolean(false);

    public ShareSummaryService(
            ShareSummaryMapper shareSummaryMapper,
            ShareSummaryLinkMapper shareSummaryLinkMapper,
            AiProviderMapper aiProviderMapper,
            AiTitleClient aiTitleClient,
            Clock clock
    ) {
        this.shareSummaryMapper = shareSummaryMapper;
        this.shareSummaryLinkMapper = shareSummaryLinkMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.aiTitleClient = aiTitleClient;
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

    public RunPage runs(Integer page, Integer size, Long taskId, String status) {
        int normalizedSize = normalizePageSize(size);
        int normalizedPage = page == null || page < 1 ? 1 : page;
        String normalizedStatus = normalizeStatusFilter(status);
        long total = shareSummaryMapper.countRuns(taskId, normalizedStatus);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalizedSize);
        int offset = (normalizedPage - 1) * normalizedSize;
        List<ShareSummaryRunRecord> items = shareSummaryMapper.selectRuns(taskId, normalizedStatus, normalizedSize, offset);
        return new RunPage(items, normalizedPage, normalizedSize, total, totalPages);
    }

    public ShareSummaryRunRecord run(long runId) {
        ShareSummaryRunRecord run = shareSummaryMapper.selectRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Share summary run was not found.");
        }
        return run;
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
        ZoneId zone = clock.getZone();
        LocalDateTime nowDateTime = LocalDateTime.ofInstant(Instant.now(clock), zone);
        LocalDate today = nowDateTime.toLocalDate();
        LocalTime runTime = parseRunTime(task.getRunTime());
        LocalDate cursorEnd = latestDueWindowEnd(periodType, today, nowDateTime, runTime, task);
        if (cursorEnd == null) {
            return List.of();
        }

        ShareSummaryRunRecord latest = shareSummaryMapper.selectLatestCompletedScheduledRun(task.getId());
        LocalDate firstEnd = latest == null
                ? previousWindowEnd(periodType, cursorEnd)
                : millisToDate(latest.getWindowEnd(), zone);
        List<Window> windows = new ArrayList<>();
        LocalDate nextEnd = nextWindowEnd(periodType, firstEnd);
        while (!nextEnd.isAfter(cursorEnd)) {
            windows.add(windowEndingAt(periodType, nextEnd, zone));
            if (windows.size() >= CATCH_UP_LIMIT) {
                break;
            }
            nextEnd = nextWindowEnd(periodType, nextEnd);
        }
        return windows;
    }

    private LocalDate latestDueWindowEnd(
            ShareSummaryPeriodType periodType,
            LocalDate today,
            LocalDateTime nowDateTime,
            LocalTime runTime,
            ShareSummaryTaskRecord task
    ) {
        return switch (periodType) {
            case DAILY -> nowDateTime.toLocalTime().compareTo(runTime) >= 0 ? today : today.minusDays(1);
            case WEEKLY -> latestWeeklyWindowEnd(nowDateTime, runTime, task.getDayOfWeek());
            case MONTHLY -> latestMonthlyWindowEnd(nowDateTime, runTime, task.getDayOfMonth());
        };
    }

    private LocalDate latestWeeklyWindowEnd(LocalDateTime nowDateTime, LocalTime runTime, Integer dayOfWeek) {
        DayOfWeek configuredDay = DayOfWeek.of(dayOfWeek);
        LocalDate scheduledDate = nowDateTime.toLocalDate().with(TemporalAdjusters.previousOrSame(configuredDay));
        if (scheduledDate.equals(nowDateTime.toLocalDate()) && nowDateTime.toLocalTime().compareTo(runTime) < 0) {
            scheduledDate = scheduledDate.minusWeeks(1);
        }
        return scheduledDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate latestMonthlyWindowEnd(LocalDateTime nowDateTime, LocalTime runTime, Integer dayOfMonth) {
        YearMonth currentMonth = YearMonth.from(nowDateTime);
        LocalDate scheduledDate = currentMonth.atDay(Math.min(dayOfMonth, currentMonth.lengthOfMonth()));
        if (nowDateTime.toLocalDate().isBefore(scheduledDate)
                || (nowDateTime.toLocalDate().equals(scheduledDate) && nowDateTime.toLocalTime().compareTo(runTime) < 0)) {
            scheduledDate = currentMonth.minusMonths(1).atDay(dayOfMonth);
        }
        return scheduledDate.withDayOfMonth(1);
    }

    private LocalDate previousWindowEnd(ShareSummaryPeriodType periodType, LocalDate windowEnd) {
        return switch (periodType) {
            case DAILY -> windowEnd.minusDays(1);
            case WEEKLY -> windowEnd.minusWeeks(1);
            case MONTHLY -> windowEnd.minusMonths(1);
        };
    }

    private LocalDate nextWindowEnd(ShareSummaryPeriodType periodType, LocalDate windowEnd) {
        return switch (periodType) {
            case DAILY -> windowEnd.plusDays(1);
            case WEEKLY -> windowEnd.plusWeeks(1);
            case MONTHLY -> windowEnd.plusMonths(1);
        };
    }

    private Window windowEndingAt(ShareSummaryPeriodType periodType, LocalDate windowEnd, ZoneId zone) {
        LocalDate windowStart = previousWindowEnd(periodType, windowEnd);
        return new Window(
                windowStart.atStartOfDay(zone).toInstant().toEpochMilli(),
                windowEnd.atStartOfDay(zone).toInstant().toEpochMilli()
        );
    }

    private LocalDate millisToDate(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
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
        return shareSummaryMapper.selectRun(run.getId());
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
        for (AiProviderRecord provider : providers) {
            try {
                AiTitleClient.AiTextResult result = aiTitleClient.generateTextResult(provider, prompt);
                durationMs += result.durationMs();
                providerNames.add(provider.getName());
                Optional<String> report = result.text()
                        .map(String::strip)
                        .filter(StringUtils::hasText);
                if (report.isPresent()) {
                    return new AiSummaryResult(report.get(), providerNames, durationMs);
                }
                lastError = "AI provider returned empty summary.";
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI summary request was interrupted.", exception);
            } catch (IOException | RuntimeException exception) {
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
            }
        }
        throw new IllegalStateException(StringUtils.hasText(lastError) ? lastError : "AI summary request failed.");
    }

    private String summaryContent(Window window, List<ShareSummaryLinkRow> links) {
        StringBuilder content = new StringBuilder();
        content.append("总结窗口：\n")
                .append(window.startMillis())
                .append(" ~ ")
                .append(window.endMillis())
                .append("\n\n链接标题列表：\n");
        for (int index = 0; index < links.size(); index++) {
            ShareSummaryLinkRow link = links.get(index);
            content.append(index + 1)
                    .append(". [")
                    .append(link.getOccurrenceCount())
                    .append("次] ")
                    .append(link.getTitle())
                    .append('\n');
        }
        return content.toString();
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
        task.setRunTime(normalizeRunTime(request.runTime()));
        task.setPrompt(required(request.prompt(), "Prompt"));
        task.setMaxLinks(normalizeMaxLinks(request.maxLinks()));
        task.setDayOfWeek(periodType == ShareSummaryPeriodType.WEEKLY ? normalizeDayOfWeek(request.dayOfWeek()) : null);
        task.setDayOfMonth(periodType == ShareSummaryPeriodType.MONTHLY ? normalizeDayOfMonth(request.dayOfMonth()) : null);
        return task;
    }

    private Window manualWindow(ShareSummaryTaskRecord task) {
        List<Window> windows = dueWindows(task);
        if (!windows.isEmpty()) {
            return windows.get(windows.size() - 1);
        }
        ShareSummaryPeriodType periodType = ShareSummaryPeriodType.fromValue(task.getPeriodType());
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        return windowEndingAt(periodType, switch (periodType) {
            case DAILY -> today;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1);
        }, zone);
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

    private Integer normalizeDayOfMonth(Integer dayOfMonth) {
        if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 28) {
            throw new IllegalArgumentException("Day of month must be between 1 and 28.");
        }
        return dayOfMonth;
    }

    private int normalizeMaxLinks(Integer maxLinks) {
        int value = maxLinks == null ? DEFAULT_MAX_LINKS : maxLinks;
        if (value < MIN_MAX_LINKS || value > MAX_MAX_LINKS) {
            throw new IllegalArgumentException("Max links must be between 1 and 500.");
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

    public record TaskRequest(
            String name,
            Boolean enabled,
            String periodType,
            String runTime,
            Integer dayOfWeek,
            Integer dayOfMonth,
            String prompt,
            Integer maxLinks
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

    public record Window(long startMillis, long endMillis) {
    }

    private record AiSummaryResult(String report, List<String> providerNames, long durationMs) {
    }
}
