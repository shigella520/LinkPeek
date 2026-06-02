package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationTaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationMapper {
    List<NotificationChannelRecord> selectChannels();

    List<NotificationChannelRecord> selectEnabledChannelsForTask(@Param("taskId") long taskId);

    NotificationChannelRecord selectChannel(@Param("id") long id);

    void insertChannel(NotificationChannelRecord channel);

    int updateChannel(NotificationChannelRecord channel);

    int deleteChannel(@Param("id") long id);

    int countTaskChannelsForChannel(@Param("channelId") long channelId);

    List<NotificationTaskRecord> selectTasks();

    List<NotificationTaskRecord> selectEnabledTasksByEventType(@Param("eventType") String eventType);

    NotificationTaskRecord selectTask(@Param("id") long id);

    void insertTask(NotificationTaskRecord task);

    int updateTask(NotificationTaskRecord task);

    int deleteTask(@Param("id") long id);

    List<Long> selectChannelIdsForTask(@Param("taskId") long taskId);

    void deleteTaskChannels(@Param("taskId") long taskId);

    void insertTaskChannel(
            @Param("taskId") long taskId,
            @Param("channelId") long channelId
    );

    void insertDelivery(NotificationDeliveryRecord delivery);

    int updateDelivery(NotificationDeliveryRecord delivery);

    int resetDeliveryForRetry(NotificationDeliveryRecord delivery);

    long countDeliveries(
            @Param("eventType") String eventType,
            @Param("taskId") Long taskId,
            @Param("channelId") Long channelId,
            @Param("status") String status
    );

    List<NotificationDeliveryRecord> selectDeliveries(
            @Param("eventType") String eventType,
            @Param("taskId") Long taskId,
            @Param("channelId") Long channelId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    NotificationDeliveryRecord selectDelivery(@Param("id") long id);

    int deleteDelivery(@Param("id") long id);
}
