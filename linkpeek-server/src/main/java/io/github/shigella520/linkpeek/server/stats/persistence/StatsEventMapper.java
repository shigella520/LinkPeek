package io.github.shigella520.linkpeek.server.stats.persistence;

import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StatsEventMapper {
    void insertEvent(StatisticsEventRecord event);

    int deleteAllEvents();

    int deleteEventsOlderThan(@Param("threshold") long threshold);

    int deleteOrphanLinks();
}
