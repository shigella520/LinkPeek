package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShareSummaryAudioMapper {
    ShareSummaryAudioConfigRecord selectConfig();

    int upsertConfig(ShareSummaryAudioConfigRecord config);

    void insertAudio(ShareSummaryAudioRecord audio);

    int updateAudio(ShareSummaryAudioRecord audio);

    int incrementPlayCount(@Param("id") long id);

    int markStaleActiveAudiosFailed(
            @Param("threshold") long threshold,
            @Param("finishedAt") long finishedAt,
            @Param("errorMessage") String errorMessage
    );

    ShareSummaryAudioRecord selectAudio(@Param("id") long id);

    ShareSummaryAudioRecord selectLatestAudio(@Param("runId") long runId);

    ShareSummaryAudioRecord selectLatestSuccessfulAudio(@Param("runId") long runId);

    ShareSummaryAudioRecord selectActiveAudio(@Param("runId") long runId);

    int selectNextAttemptNo(@Param("runId") long runId);

    List<ShareSummaryAudioRecord> selectAudiosForRun(@Param("runId") long runId);

    int deleteAudiosForRun(@Param("runId") long runId);
}
