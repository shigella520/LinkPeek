package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static io.github.shigella520.linkpeek.server.admin.service.NotificationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationConfigServiceTest {
    @Test
    void privateWebhookIsRejectedByDefaultOnSaveAndSend() throws Exception {
        NotificationMapper mapper = mock(NotificationMapper.class);
        HttpClient client = mock(HttpClient.class);
        NotificationChannelRecord channel = channel();
        channel.setUrl("http://192.168.31.11:1234/api/v1/message/text");
        when(mapper.selectChannel(3L)).thenReturn(channel);
        NotificationConfigService service = configService(mapper, mock(ExecutorService.class), client, new LinkPeekProperties());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createChannel(channelRequest(channel.getUrl())));
        assertTrue(error.getMessage().contains("WEBHOOK_ALLOWED_PRIVATE_HOSTS"));
        verify(mapper, never()).insertChannel(any());
        assertTrue(service.testChannel(3L).errorMessage().contains("WEBHOOK_ALLOWED_PRIVATE_HOSTS"));
        verify(client, never()).send(any(), any());
    }

    @Test
    void privateWebhookAllowlistAppliesToSaveAndSend() throws Exception {
        NotificationMapper mapper = mock(NotificationMapper.class);
        HttpClient client = mock(HttpClient.class);
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setWebhookAllowedPrivateHosts(List.of("192.168.31.12", " 192.168.31.11 "));
        NotificationChannelRecord channel = channel();
        channel.setUrl("http://192.168.31.11:1234/api/v1/message/text");
        doAnswer(invocation -> {
            NotificationChannelRecord saved = invocation.getArgument(0);
            saved.setId(3L);
            return null;
        }).when(mapper).insertChannel(any());
        when(mapper.selectChannel(3L)).thenReturn(channel);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        NotificationConfigService service = configService(mapper, mock(ExecutorService.class), client, properties);

        assertEquals(channel.getUrl(), service.createChannel(channelRequest(channel.getUrl())).url());
        assertTrue(service.testChannel(3L).success());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertEquals(channel.getUrl(), request.getValue().uri().toString());

        properties.setWebhookAllowedPrivateHosts(List.of());
        assertTrue(service.testChannel(3L).errorMessage().contains("WEBHOOK_ALLOWED_PRIVATE_HOSTS"));
        verify(client).send(any(), any());
    }

    @Test
    void privateWebhookAllowlistDoesNotAllowOtherHostsOrSpecialAddresses() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setWebhookAllowedPrivateHosts(List.of(
                "192.168.31.11", "127.0.0.1", "169.254.169.254", "0.0.0.0", "224.0.0.1"));
        NotificationConfigService service = configService(mapper, mock(ExecutorService.class), mock(HttpClient.class), properties);

        for (String host : List.of("192.168.31.110", "10.0.0.1", "127.0.0.1", "169.254.169.254", "0.0.0.0", "224.0.0.1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createChannel(channelRequest("http://" + host + "/webhook")), host);
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.createChannel(channelRequest("ftp://192.168.31.11/webhook")));
        verify(mapper, never()).insertChannel(any());
    }

    private NotificationConfigService configService(NotificationMapper mapper, ExecutorService executor,
                                                    HttpClient client, LinkPeekProperties properties) {
        return new NotificationConfigService(
                mapper,
                new NotificationTemplateService(OBJECT_MAPPER),
                OBJECT_MAPPER,
                deliveryService(mapper, executor, client, properties),
                CLOCK
        );
    }
}
