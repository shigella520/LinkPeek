package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShareSummaryImageMapper {
    ShareSummaryImageConfigRecord selectConfig();

    int upsertConfig(ShareSummaryImageConfigRecord config);

    void insertImage(ShareSummaryImageRecord image);

    int updateImage(ShareSummaryImageRecord image);

    ShareSummaryImageRecord selectImage(@Param("id") long id);

    ShareSummaryImageRecord selectImageByPublicToken(@Param("publicToken") String publicToken);

    ShareSummaryImageRecord selectLatestImage(@Param("runId") long runId);

    ShareSummaryImageRecord selectLatestSuccessfulImage(@Param("runId") long runId);

    ShareSummaryImageRecord selectActiveImage(@Param("runId") long runId);

    int selectNextAttemptNo(@Param("runId") long runId);

    List<ShareSummaryImageRecord> selectImagesForRun(@Param("runId") long runId);

    int deleteImagesForRun(@Param("runId") long runId);
}
