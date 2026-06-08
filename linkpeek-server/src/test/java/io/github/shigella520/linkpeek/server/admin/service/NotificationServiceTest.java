package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {
    @Test
    void deleteDeliveryRemovesExistingDelivery() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryRecord delivery = new NotificationDeliveryRecord();
        delivery.setId(9L);
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.deleteDelivery(9L)).thenReturn(1);

        NotificationService service = service(mapper);

        NotificationService.DeleteResponse response = service.deleteDelivery(9L);

        assertEquals(1, response.deleted());
        verify(mapper).deleteDelivery(9L);
    }

    @Test
    void deleteDeliveryRejectsMissingDelivery() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationService service = service(mapper);

        assertThrows(IllegalArgumentException.class, () -> service.deleteDelivery(404L));

        verify(mapper, never()).deleteDelivery(404L);
    }

    @Test
    void retryDeliveryResetsFailedDeliveryAndSubmitsWebhook() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setRequestBody("{\"text\":\"hello\"}");
        NotificationChannelRecord channel = channel();
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel);
        when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);

        NotificationService service = service(mapper, executor);

        NotificationDeliveryRecord response = service.retryDelivery(9L);

        assertEquals("PENDING", response.getStatus());
        assertEquals(0, response.getAttemptCount());
        assertEquals(channel.getUrl(), response.getRequestUrl());
        assertEquals("{\"text\":\"hello\"}", response.getRequestBody());
        Assertions.assertNull(response.getResponseStatus());
        Assertions.assertNull(response.getResponseBodySnapshot());
        Assertions.assertNull(response.getErrorMessage());
        Assertions.assertNull(response.getFinishedAt());
        verify(mapper).resetDeliveryForRetry(delivery);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
    }

    @Test
    void retryDeliveryResetsSuccessfulDeliveryAndSubmitsWebhook() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setStatus("SUCCESS");
        delivery.setRequestBody("{\"text\":\"hello\"}");
        NotificationChannelRecord channel = channel();
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel);
        when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);
        NotificationService service = service(mapper, executor);

        NotificationDeliveryRecord response = service.retryDelivery(9L);

        assertEquals("PENDING", response.getStatus());
        assertEquals(0, response.getAttemptCount());
        verify(mapper).resetDeliveryForRetry(delivery);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
    }

    @Test
    void retryDeliveryRejectsTruncatedLegacySnapshot() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setRequestBodySnapshot("x".repeat(8_000));
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel());
        NotificationService service = service(mapper);

        assertThrows(IllegalStateException.class, () -> service.retryDelivery(9L));

        verify(mapper, never()).resetDeliveryForRetry(delivery);
    }

    private NotificationService service(NotificationMapper mapper) {
        return service(mapper, mock(ExecutorService.class));
    }

    private NotificationService service(NotificationMapper mapper, ExecutorService executor) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new NotificationService(
                mapper,
                new NotificationTemplateService(objectMapper),
                objectMapper,
                HttpClient.newHttpClient(),
                executor,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-06-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );
    }

    private NotificationDeliveryRecord failedDelivery() {
        NotificationDeliveryRecord delivery = new NotificationDeliveryRecord();
        delivery.setId(9L);
        delivery.setEventType("SHARE_SUMMARY_IMAGE_SUCCESS");
        delivery.setChannelId(3L);
        delivery.setStatus("FAILED");
        delivery.setAttemptCount(3);
        delivery.setRequestUrl("https://old.example.test/webhook");
        delivery.setRequestBodySnapshot("{\"text\":\"hello\"}");
        delivery.setResponseStatus(500);
        delivery.setResponseBodySnapshot("error");
        delivery.setErrorMessage("Webhook returned HTTP 500");
        delivery.setDurationMs(50);
        delivery.setFinishedAt(1770000000000L);
        return delivery;
    }

    private NotificationChannelRecord channel() {
        NotificationChannelRecord channel = new NotificationChannelRecord();
        channel.setId(3L);
        channel.setName("Webhook");
        channel.setEnabled(true);
        channel.setUrl("https://example.test/webhook");
        channel.setTimeoutSeconds(10);
        return channel;
    }
}
