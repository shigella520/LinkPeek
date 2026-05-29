package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryLinkRow;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryLinkMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.ai.AiTitleClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShareSummaryServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void dailyDueWindowUsesPreviousCompleteNaturalDayAfterRunTime() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-30T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("DAILY", "09:00", null, null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-29", "2026-05-30");
    }

    @Test
    void dailyDueWindowFallsBackBeforeRunTime() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-05-30T00:30:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("DAILY", "09:00", null, null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-28", "2026-05-29");
    }

    @Test
    void weeklyDueWindowSummarizesPreviousNaturalWeekRegardlessOfConfiguredWeekday() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-06-03T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("WEEKLY", "09:00", 3, null));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-25", "2026-06-01");
    }

    @Test
    void monthlyDueWindowSummarizesPreviousNaturalMonth() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryService service = service(mapper, "2026-06-15T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("MONTHLY", "09:00", null, 15));

        assertEquals(1, windows.size());
        assertWindow(windows.get(0), "2026-05-01", "2026-06-01");
    }

    @Test
    void dueWindowsCatchUpFromLatestCompletedScheduledRunAndStopsAtSevenWindows() {
        FakeShareSummaryMapper mapper = new FakeShareSummaryMapper();
        ShareSummaryRunRecord latest = new ShareSummaryRunRecord();
        latest.setWindowEnd(toMillis("2026-05-20"));
        mapper.latestCompletedScheduledRun = latest;
        ShareSummaryService service = service(mapper, "2026-05-30T02:00:00Z");

        List<ShareSummaryService.Window> windows = service.dueWindows(task("DAILY", "09:00", null, null));

        assertEquals(7, windows.size());
        assertWindow(windows.get(0), "2026-05-20", "2026-05-21");
        assertWindow(windows.get(6), "2026-05-26", "2026-05-27");
    }

    @Test
    void monthlyTaskRejectsDayAfterTwentyEight() {
        ShareSummaryService service = service(new FakeShareSummaryMapper(), "2026-05-30T02:00:00Z");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createTask(new ShareSummaryService.TaskRequest(
                        "月总结",
                        true,
                        "MONTHLY",
                        "09:00",
                        null,
                        29,
                        "总结",
                        100
                ))
        );
    }

    private ShareSummaryService service(FakeShareSummaryMapper mapper, String instant) {
        return new ShareSummaryService(
                mapper,
                new FakeShareSummaryLinkMapper(),
                new FakeAiProviderMapper(),
                new AiTitleClient(null, null),
                null,
                Clock.fixed(Instant.parse(instant), ZONE)
        );
    }

    private ShareSummaryTaskRecord task(String periodType, String runTime, Integer dayOfWeek, Integer dayOfMonth) {
        ShareSummaryTaskRecord task = new ShareSummaryTaskRecord();
        task.setId(1L);
        task.setName("summary");
        task.setEnabled(true);
        task.setPeriodType(periodType);
        task.setRunTime(runTime);
        task.setDayOfWeek(dayOfWeek);
        task.setDayOfMonth(dayOfMonth);
        task.setPrompt("prompt");
        task.setMaxLinks(100);
        return task;
    }

    private void assertWindow(ShareSummaryService.Window window, String startDate, String endDate) {
        assertEquals(toMillis(startDate), window.startMillis());
        assertEquals(toMillis(endDate), window.endMillis());
    }

    private long toMillis(String localDate) {
        return LocalDate.parse(localDate).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private static final class FakeShareSummaryMapper implements ShareSummaryMapper {
        private ShareSummaryRunRecord latestCompletedScheduledRun;
        private long nextTaskId = 1;

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
            return null;
        }

        @Override
        public void insertTask(ShareSummaryTaskRecord task) {
            task.setId(nextTaskId++);
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
        }

        @Override
        public int updateRun(ShareSummaryRunRecord run) {
            return 0;
        }

        @Override
        public int markStaleRunningRunsFailed(long threshold, long finishedAt) {
            return 0;
        }

        @Override
        public ShareSummaryRunRecord selectRun(long id) {
            return null;
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
        @Override
        public int countCreatedEvents(long windowStart, long windowEnd) {
            return 0;
        }

        @Override
        public List<ShareSummaryLinkRow> selectSummaryLinks(long windowStart, long windowEnd) {
            return new ArrayList<>();
        }
    }

    private static final class FakeAiProviderMapper implements AiProviderMapper {
        @Override
        public List<AiProviderRecord> selectAllProviders() {
            return List.of();
        }

        @Override
        public List<AiProviderRecord> selectEnabledProviders() {
            return List.of();
        }

        @Override
        public AiProviderRecord selectProvider(long id) {
            return null;
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
            return 0;
        }

        @Override
        public int deleteProvider(long id) {
            return 0;
        }
    }
}
