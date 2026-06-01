package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;

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

    private NotificationService service(NotificationMapper mapper) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new NotificationService(
                mapper,
                new NotificationTemplateService(objectMapper),
                objectMapper,
                HttpClient.newHttpClient(),
                mock(ExecutorService.class),
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-06-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );
    }
}
