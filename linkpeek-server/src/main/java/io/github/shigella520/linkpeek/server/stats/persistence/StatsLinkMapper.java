package io.github.shigella520.linkpeek.server.stats.persistence;

import io.github.shigella520.linkpeek.server.stats.model.StatisticsLinkRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StatsLinkMapper {
    void upsertLink(StatisticsLinkRecord link);
}
