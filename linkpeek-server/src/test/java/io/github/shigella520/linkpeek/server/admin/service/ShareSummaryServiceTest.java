package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryLinkRow;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryLinkMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.ai.AiProviderDowngradeService;
import io.github.shigella520.linkpeek.server.ai.AiTextPrompt;
import io.github.shigella520.linkpeek.server.ai.AiTitleClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShareSummaryServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void dailyDueWindowUsesCurrentDayStartToTriggerAfterRunTime() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-30T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("DAILY", "09:00", null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-30T00:00", "2026-05-30T09:00");
    }

    @Test
    void dailyDueWindowFallsBackToPreviousTriggerBeforeRunTime() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-30T00:30:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("DAILY", "09:00", null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-29T00:00", "2026-05-29T09:00");
    }

    @Test
    void weeklyDueWindowUsesCurrentWeekMondayToConfiguredWeekdayTrigger() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-06-03T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("WEEKLY", "09:00", 3));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-06-01T00:00", "2026-06-03T09:00");
    }

    @Test
    void monthlyDueWindowUsesCurrentMonthStartToMonthEndTrigger() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-31T15:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("MONTHLY", "22:00", null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-01T00:00", "2026-05-31T22:00");
    }

    @Test
    void monthlyDueWindowFallsBackBeforeMonthEndTrigger() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-30T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("MONTHLY", "09:00", null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-04-01T00:00", "2026-04-30T09:00");
    }

    @Test
    void dueWindowsCatchUpFromLatestCompletedScheduledRunAndStopsAtSevenWindows() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryRunRecord latest = new ShareSummaryRunRecord();
        latest.setWindowEnd(toMillis("2026-05-20T09:00"));
        mapper.latestCompletedScheduledRun = latest;
        ShareSummaryService service = service(mapper, "2026-05-30T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("DAILY", "09:00", null));

        assertEquals(7, windows.size());
        assertWindow(windows.get(0), "2026-05-21T00:00", "2026-05-21T09:00");
        assertWindow(windows.get(6), "2026-05-27T00:00", "2026-05-27T09:00");
    }

    @Test
    void monthlyTaskDoesNotRequireDayOfMonth() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-30T02:00:00Z");

        ShareSummaryTaskRecord task = service.createTask(new ShareSummaryService.TaskRequest(
                "月总结",
                true,
                "MONTHLY",
                "09:00",
                null,
                "总结",
                100,
                100
        ));

        assertEquals("MONTHLY", task.getPeriodType());
        assertNull(task.getDayOfWeek());
    }

    @Test
    void manualWindowUsesCurrentPeriodStartToActualCurrentTime() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        mapper.task = task("WEEKLY", "09:00", 3);
        ShareSummaryService service = service(mapper, "2026-06-04T02:00:00Z");

        ShareSummaryRunRecord run = service.runTask(1L);

        assertEquals(toMillis("2026-06-01T00:00"), run.getWindowStart());
        assertEquals(toMillis("2026-06-04T10:00"), run.getWindowEnd());
    }

    @Test
    void deleteRunRemovesExistingRun() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setId(12L);
        run.setStatus("SUCCESS");
        mapper.run = run;
        ShareSummaryService service = service(mapper, "2026-06-04T02:00:00Z");

        ShareSummaryService.DeleteRunResponse response = service.deleteRun(12L);

        assertEquals(1, response.deleted());
        assertEquals(0, response.deletedImages());
        assertNull(mapper.run);
    }

    @Test
    void deleteRunRejectsRunningRun() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setId(12L);
        run.setStatus("RUNNING");
        mapper.run = run;
        ShareSummaryService service = service(mapper, "2026-06-04T02:00:00Z");

        assertThrows(IllegalStateException.class, () -> service.deleteRun(12L));
    }

    @Test
    void runTaskSkipsAiWhenTitleCountIsBelowConfiguredMinimum() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryTaskRecord task = task("DAILY", "09:00", null);
        task.setMinLinks(2);
        mapper.task = task;
        ShareSummaryLinkRow link = new ShareSummaryLinkRow();
        link.setTitle("数据库标题 A");
        link.setOccurrenceCount(1);
        FakeShareSummaryLinkMapper linkMapper = new FakeShareSummaryLinkMapper();
        linkMapper.summaryLinks = List.of(link);
        ShareSummaryService service = service(mapper, linkMapper, "2026-06-04T02:00:00Z");

        ShareSummaryRunRecord run = service.runTask(1L);

        assertEquals("EMPTY", run.getStatus());
        assertEquals(1, run.getUniqueLinkCount());
        assertEquals(1, run.getInputLinkCount());
        org.junit.jupiter.api.Assertions.assertTrue(run.getErrorMessage().contains("below the configured minimum 2"));
    }

    @Test
    void aiProviderTimeoutRecordsDowngradeAndFallsBackToNextProvider() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        mapper.task = task("DAILY", "09:00", null);
        ShareSummaryLinkRow link = new ShareSummaryLinkRow();
        link.setTitle("数据库标题 A");
        link.setOccurrenceCount(1);
        FakeShareSummaryLinkMapper linkMapper = new FakeShareSummaryLinkMapper();
        linkMapper.summaryLinks = List.of(link);
        FakeAiProviderMapper providerMapper = new FakeAiProviderMapper();
        AiProviderRecord timeoutProvider = provider(1L, "timeout-provider", 10);
        AiProviderRecord fallbackProvider = provider(2L, "fallback-provider", 20);
        providerMapper.providers = List.of(timeoutProvider, fallbackProvider);
        FakeAiTitleClient aiTitleClient = new FakeAiTitleClient(timeoutProvider.getId());
        AiProviderDowngradeService downgradeService = new AiProviderDowngradeService(
                new EnabledAutoDowngradeConfigMapper(),
                providerMapper,
                Clock.fixed(Instant.parse("2026-06-04T02:00:00Z"), ZONE)
        );
        ShareSummaryService service = service(
                mapper,
                linkMapper,
                providerMapper,
                aiTitleClient,
                downgradeService,
                "2026-06-04T02:00:00Z"
        );

        ShareSummaryRunRecord run = service.runTask(1L);

        assertEquals("SUCCESS", run.getStatus());
        assertEquals("timeout-provider/fallback-provider", run.getAiProviderNames());
        assertEquals("fallback summary", run.getReport());
        assertEquals(List.of(fallbackProvider.getId(), timeoutProvider.getId()), providerMapper.selectAllProviders().stream()
                .map(AiProviderRecord::getId)
                .toList());
    }

    private ShareSummaryService service(FakeShareSummaryMapper mapper, String instant) {
        return service(mapper, new FakeShareSummaryLinkMapper(), instant);
    }

    private ShareSummaryService service(FakeShareSummaryMapper mapper, ShareSummaryLinkMapper linkMapper, String instant) {
        return service(
                mapper,
                linkMapper,
                new FakeAiProviderMapper(),
                new AiTitleClient(null, null),
                null,
                instant
        );
    }

    private ShareSummaryService service(
            FakeShareSummaryMapper mapper,
            ShareSummaryLinkMapper linkMapper,
            AiProviderMapper providerMapper,
            AiTitleClient aiTitleClient,
            AiProviderDowngradeService downgradeService,
            String instant
    ) {
        return new ShareSummaryService(
                mapper,
                linkMapper,
                providerMapper,
                aiTitleClient,
                null,
                downgradeService,
                Clock.fixed(Instant.parse(instant), ZONE)
        );
    }

    private ShareSummaryTaskRecord task(String periodType, String runTime, Integer dayOfWeek) {
        ShareSummaryTaskRecord task = new ShareSummaryTaskRecord();
        task.setId(1L);
        task.setName("summary");
        task.setEnabled(true);
        task.setPeriodType(periodType);
        task.setRunTime(runTime);
        task.setDayOfWeek(dayOfWeek);
        task.setPrompt("prompt");
        task.setMaxLinks(100);
        task.setMinLinks(1);
        return task;
    }

    private void assertWindow(ShareSummaryService.Window window, String startDateTime, String endDateTime) {
        assertEquals(toMillis(startDateTime), window.startMillis());
        assertEquals(toMillis(endDateTime), window.endMillis());
    }

    private long toMillis(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant().toEpochMilli();
    }

    private AiProviderRecord provider(Long id, String name, int sortOrder) {
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(id);
        provider.setName(name);
        provider.setEnabled(true);
        provider.setSortOrder(sortOrder);
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setModel("test-model");
        provider.setApiKey("sk-test");
        return provider;
    }

    private static final class FakeShareSummaryMapper implements ShareSummaryMapper {
        private ShareSummaryRunRecord latestCompletedScheduledRun;
        private ShareSummaryTaskRecord task;
        private ShareSummaryRunRecord run;
        private long nextTaskId = 1;
        private long nextRunId = 1;

        @Override
        public List<ShareSummaryTaskRecord> selectTasks() {
            return List.of();
        }

        @Override
        public List<ShareSummaryTaskRecord> selectEnabledTasks() {
            return List.of();
        }

        @Override
        public ShareSummaryTaskRecord selectTask(long id) {
            return task != null && task.getId() == id ? task : null;
        }

        @Override
        public void insertTask(ShareSummaryTaskRecord task) {
            task.setId(nextTaskId++);
            this.task = task;
        }

        @Override
        public int updateTask(ShareSummaryTaskRecord task) {
            return 0;
        }

        @Override
        public int deleteTask(long id, long deletedAt) {
            return 0;
        }

        @Override
        public void insertRun(ShareSummaryRunRecord run) {
            run.setId(nextRunId++);
            this.run = run;
        }

        @Override
        public int updateRun(ShareSummaryRunRecord run) {
            this.run = run;
            return 0;
        }

        @Override
        public int markStaleRunningRunsFailed(long threshold, long finishedAt) {
            return 0;
        }

        @Override
        public ShareSummaryRunRecord selectRun(long id) {
            return run != null && run.getId() == id ? run : null;
        }

        @Override
        public int deleteRun(long id) {
            if (run != null && run.getId() == id) {
                run = null;
                return 1;
            }
            return 0;
        }

        @Override
        public ShareSummaryRunRecord selectLatestCompletedScheduledRun(long taskId) {
            return latestCompletedScheduledRun;
        }

        @Override
        public ShareSummaryRunRecord selectScheduledRunForWindow(long taskId, long windowStart, long windowEnd) {
            return null;
        }

        @Override
        public long countRuns(Long taskId, String status) {
            return 0;
        }

        @Override
        public List<ShareSummaryRunRecord> selectRuns(Long taskId, String status, int limit, int offset) {
            return List.of();
        }
    }

    private static final class FakeShareSummaryLinkMapper implements ShareSummaryLinkMapper {
        private List<ShareSummaryLinkRow> summaryLinks = new ArrayList<>();

        @Override
        public int countCreatedEvents(long windowStart, long windowEnd) {
            return summaryLinks.size();
        }

        @Override
        public List<ShareSummaryLinkRow> selectSummaryLinks(long windowStart, long windowEnd) {
            return summaryLinks;
        }
    }

    private static final class FakeAiProviderMapper implements AiProviderMapper {
        private List<AiProviderRecord> providers = List.of();

        @Override
        public List<AiProviderRecord> selectAllProviders() {
            return providers.stream()
                    .sorted(Comparator.comparingInt(AiProviderRecord::getSortOrder).thenComparing(AiProviderRecord::getId))
                    .toList();
        }

        @Override
        public List<AiProviderRecord> selectEnabledProviders() {
            return selectAllProviders().stream().filter(AiProviderRecord::isEnabled).toList();
        }

        @Override
        public AiProviderRecord selectProvider(long id) {
            return providers.stream()
                    .filter(provider -> provider.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void insertProvider(AiProviderRecord provider) {
        }

        @Override
        public int updateProvider(AiProviderRecord provider) {
            return 0;
        }

        @Override
        public int updateProviderEnabled(long id, boolean enabled, long updatedAt) {
            return 0;
        }

        @Override
        public int updateProviderSortOrder(long id, int sortOrder, long updatedAt) {
            AiProviderRecord provider = selectProvider(id);
            if (provider == null) {
                return 0;
            }
            provider.setSortOrder(sortOrder);
            provider.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public int deleteProvider(long id) {
            return 0;
        }
    }

    private static final class FakeAiTitleClient extends AiTitleClient {
        private final Long timeoutProviderId;

        private FakeAiTitleClient(Long timeoutProviderId) {
            super(null, null);
            this.timeoutProviderId = timeoutProviderId;
        }

        @Override
        public AiTextResult generateTextResult(AiProviderRecord provider, AiTextPrompt prompt) throws IOException, InterruptedException {
            if (provider.getId().equals(timeoutProviderId)) {
                throw new HttpTimeoutException("request timed out");
            }
            return new AiTextResult(Optional.of("fallback summary"), 12);
        }
    }

    private static final class EnabledAutoDowngradeConfigMapper implements ProviderConfigMapper {
        @Override
        public List<io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord> selectAllConfigs() {
            return List.of();
        }

        @Override
        public List<io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord> selectProviderConfigs(String providerId) {
            return List.of();
        }

        @Override
        public io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord selectConfig(String providerId, String configKey) {
            io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord record = new io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord();
            record.setProviderId(providerId);
            record.setConfigKey(configKey);
            if (AiProviderDowngradeService.AUTO_DOWNGRADE_ENABLED_KEY.equals(configKey)) {
                record.setConfigValue("true");
            } else if (AiProviderDowngradeService.AUTO_DOWNGRADE_TIMEOUT_THRESHOLD_KEY.equals(configKey)) {
                record.setConfigValue("1");
            }
            return record;
        }

        @Override
        public void upsertConfig(io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord config) {
        }
    }
}
