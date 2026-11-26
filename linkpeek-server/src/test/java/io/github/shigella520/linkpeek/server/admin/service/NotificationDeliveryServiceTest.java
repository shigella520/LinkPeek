package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static io.github.shigella520.linkpeek.server.admin.service.NotificationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryServiceTest {
    @Test
    void deleteDeliveryRemovesExistingDelivery() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryRecord delivery = new NotificationDeliveryRecord();
        delivery.setId(9L);
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.deleteDelivery(9L)).thenReturn(1);

        NotificationDeliveryService service = service(mapper);

        NotificationDeleteResponse response = service.deleteDelivery(9L);

        assertEquals(1, response.deleted());
        verify(mapper).deleteDelivery(9L);
    }

    @Test
    void deleteDeliveryRejectsMissingDelivery() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryService service = service(mapper);

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

        NotificationDeliveryService service = service(mapper, executor);

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
        NotificationDeliveryService service = service(mapper, executor);

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
        NotificationDeliveryService service = service(mapper);

        assertThrows(IllegalStateException.class, () -> service.retryDelivery(9L));

        verify(mapper, never()).resetDeliveryForRetry(delivery);
    }

    @Test
    void oldPendingIsMarkedForReviewWithoutSendingOrChangingStoredStatus() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryRecord old = failedDelivery();
        old.setStatus("PENDING"); old.setCreatedAt(1L);
        when(mapper.selectDelivery(9L)).thenReturn(old);
        NotificationDeliveryService service = service(mapper, executor);
        assertTrue(service.delivery(9L).isPendingExpired());
        assertEquals("PENDING", old.getStatus());
        verify(executor, never()).execute(any());
        verify(mapper, never()).updateDelivery(any());
    }

    @Test
    void temporaryDnsFailureIsRetriedButBlockedAddressesAreNot() throws Exception {
        for (boolean dnsFailure : List.of(true, false)) {
            NotificationMapper mapper = mock(NotificationMapper.class);
            HttpClient client = mock(HttpClient.class);
            NotificationDeliveryRecord delivery = failedDelivery();
            delivery.setRequestBody("{}");
            when(mapper.selectDelivery(9L)).thenReturn(delivery);
            when(mapper.selectChannel(3L)).thenReturn(channel());
            when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);
            NotificationDeliveryService service = org.mockito.Mockito.spy(service(mapper, directExecutor(), client, new LinkPeekProperties()));
            if (dnsFailure) {
                org.mockito.Mockito.doThrow(new java.net.UnknownHostException("temporary DNS failure"))
                        .when(service).resolveWebhookHost(any());
            } else {
                org.mockito.Mockito.doReturn(new java.net.InetAddress[]{java.net.InetAddress.getLoopbackAddress()})
                        .when(service).resolveWebhookHost(any());
            }
            service.retryDelivery(9L);
            assertEquals(dnsFailure ? 3 : 1, delivery.getAttemptCount());
            assertEquals("FAILED", delivery.getStatus());
            verify(client, never()).send(any(), any());
        }
    }

    @Test
    void duplicateRetryIsRejectedWhileDeliveryIsQueued() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryRecord delivery = failedDelivery(); delivery.setRequestBody("{}");
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel());
        when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryService service = service(mapper, executor);
        service.retryDelivery(9L);
        assertThrows(IllegalStateException.class, () -> service.retryDelivery(9L));
        verify(executor).execute(any());
    }

    @Test
    void startupRecoveryResubmitsRecentPendingAndFailsDeliveriesWithoutChannel() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryRecord recent = failedDelivery();
        recent.setStatus("PENDING"); recent.setRequestBody("{}");
        NotificationDeliveryRecord orphan = failedDelivery();
        orphan.setId(10L); orphan.setStatus("PENDING"); orphan.setChannelId(4L); orphan.setRequestBody("{}");
        NotificationChannelRecord disabled = channel();
        disabled.setId(4L); disabled.setEnabled(false);
        long now = Instant.parse("2026-06-04T02:00:00Z").toEpochMilli();
        when(mapper.selectPendingDeliveriesCreatedSince(now - Duration.ofHours(24).toMillis(), 500))
                .thenReturn(List.of(recent, orphan));
        when(mapper.selectChannel(3L)).thenReturn(channel());
        when(mapper.selectChannel(4L)).thenReturn(disabled);
        NotificationDeliveryService service = service(mapper, executor);

        assertEquals(1, service.recoverPendingDeliveries());

        verify(executor).execute(any());
        assertEquals("FAILED", orphan.getStatus());
        assertEquals("NOTIFICATION_CHANNEL_UNAVAILABLE", orphan.getErrorMessage());
        verify(mapper).updateDelivery(orphan);
        // Already queued deliveries are not submitted twice by a later recovery pass.
        assertEquals(0, service.recoverPendingDeliveries());
        verify(executor).execute(any());
    }

    @Test
    void rateLimitIsRetriedButClientErrorsAndBadHeadersAreNot() throws Exception {
        record Case(int status, String headersJson, int expectedAttempts) {}
        for (Case c : List.of(new Case(429, null, 3), new Case(503, null, 3), new Case(400, null, 1), new Case(200, "{bad json", 1))) {
            NotificationMapper mapper = mock(NotificationMapper.class);
            HttpClient client = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<Object> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(c.status());
            when(client.send(any(), any())).thenReturn(response);
            NotificationDeliveryRecord delivery = failedDelivery();
            delivery.setRequestBody("{}");
            NotificationChannelRecord channel = channel();
            channel.setHeadersJson(c.headersJson());
            when(mapper.selectDelivery(9L)).thenReturn(delivery);
            when(mapper.selectChannel(3L)).thenReturn(channel);
            when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);
            NotificationDeliveryService service = org.mockito.Mockito.spy(service(mapper, directExecutor(), client, new LinkPeekProperties()));
            org.mockito.Mockito.doReturn(new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")})
                    .when(service).resolveWebhookHost(any());
            org.mockito.Mockito.doNothing().when(service).sleepBeforeRetry(org.mockito.ArgumentMatchers.anyInt());

            service.retryDelivery(9L);

            assertEquals(c.expectedAttempts(), delivery.getAttemptCount(), "status " + c.status());
            assertEquals("FAILED", delivery.getStatus());
        }
    }

    @Test
    void webhookCarriesStableDeliveryIdForReceiverDeduplication() throws Exception {
        NotificationMapper mapper = mock(NotificationMapper.class);
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(client.send(any(), any())).thenReturn(response);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setRequestBody("{}");
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel());
        when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);
        NotificationDeliveryService service = org.mockito.Mockito.spy(service(mapper, directExecutor(), client, new LinkPeekProperties()));
        org.mockito.Mockito.doReturn(new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")})
                .when(service).resolveWebhookHost(any());

        service.retryDelivery(9L);

        ArgumentCaptor<java.net.http.HttpRequest> request = ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertEquals(Optional.of("9"), request.getValue().headers().firstValue("X-LinkPeek-Delivery-Id"));
        assertEquals("SUCCESS", delivery.getStatus());
    }

    private NotificationDeliveryService service(NotificationMapper mapper) {
        return service(mapper, directExecutor());
    }

    private NotificationDeliveryService service(NotificationMapper mapper, ExecutorService executor) {
        return service(mapper, executor, HttpClient.newHttpClient(), new LinkPeekProperties());
    }

    private NotificationDeliveryService service(NotificationMapper mapper, ExecutorService executor,
                                                HttpClient client, LinkPeekProperties properties) {
        return deliveryService(mapper, executor, client, properties);
    }
}
