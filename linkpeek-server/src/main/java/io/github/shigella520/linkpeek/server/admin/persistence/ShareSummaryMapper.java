package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShareSummaryMapper {
    List<ShareSummaryTaskRecord> selectTasks();

    List<ShareSummaryTaskRecord> selectEnabledTasks();

    ShareSummaryTaskRecord selectTask(@Param("id") long id);

    void insertTask(ShareSummaryTaskRecord task);

    int updateTask(ShareSummaryTaskRecord task);

    int deleteTask(
            @Param("id") long id,
            @Param("deletedAt") long deletedAt
    );

    void insertRun(ShareSummaryRunRecord run);

    int updateRun(ShareSummaryRunRecord run);

    int markStaleRunningRunsFailed(
            @Param("threshold") long threshold,
            @Param("finishedAt") long finishedAt
    );

    ShareSummaryRunRecord selectRun(@Param("id") long id);

    ShareSummaryRunRecord selectLatestCompletedScheduledRun(@Param("taskId") long taskId);

    ShareSummaryRunRecord selectScheduledRunForWindow(
            @Param("taskId") long taskId,
            @Param("windowStart") long windowStart,
            @Param("windowEnd") long windowEnd
    );

    long countRuns(
            @Param("taskId") Long taskId,
            @Param("status") String status
    );

    List<ShareSummaryRunRecord> selectRuns(
            @Param("taskId") Long taskId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
