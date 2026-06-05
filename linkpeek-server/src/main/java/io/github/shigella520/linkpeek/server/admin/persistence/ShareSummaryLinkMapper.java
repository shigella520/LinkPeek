package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryLinkRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShareSummaryLinkMapper {
    int countCreatedEvents(
            @Param("windowStart") long windowStart,
            @Param("windowEnd") long windowEnd
    );

    List<ShareSummaryLinkRow> selectSummaryLinks(
            @Param("windowStart") long windowStart,
            @Param("windowEnd") long windowEnd
    );
}
