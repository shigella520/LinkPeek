package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationTemplateServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationTemplateService service = new NotificationTemplateService(objectMapper);

    @Test
    void rendersStringAndNumericPlaceholdersToValidJson() throws Exception {
        String rendered = service.render(
                NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS,
                """
                        {"title":"{{image.ogTitle}}","count":{{run.linkCount}},"missing":"{{run.aiProviderNames}}"}
                        """,
                Map.of(
                        "image.ogTitle", "Title \"quoted\"",
                        "run.linkCount", 7
                )
        );

        JsonNode json = objectMapper.readTree(rendered);
        assertEquals("Title \"quoted\"", json.path("title").asText());
        assertEquals(7, json.path("count").asInt());
        assertEquals("", json.path("missing").asText());
    }

    @Test
    void rendersPlainTextNotificationTemplates() {
        String rendered = service.render(
                NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS,
                "{{run.taskName}} 已生成分享图：{{image.ogShareUrl}}，共 {{run.linkCount}} 条链接",
                Map.of(
                        "run.taskName", "每日速报",
                        "image.ogShareUrl", "https://example.com/report",
                        "run.linkCount", 12
                )
        );

        assertEquals("每日速报 已生成分享图：https://example.com/report，共 12 条链接", rendered);
    }

    @Test
    void rejectsUnknownPlaceholders() {
        NotificationTemplateService.TemplateValidationException exception = assertThrows(
                NotificationTemplateService.TemplateValidationException.class,
                () -> service.validateTemplate(
                        NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS,
                        "{\"bad\":\"{{run.notExists}}\"}"
                )
        );

        assertEquals("run.notExists", exception.placeholders().get(0));
    }

    @Test
    void allowsTemplatesThatAreNotJson() {
        service.validateTemplate(
                NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS,
                "标题：{{image.ogTitle}}\n链接：{{image.ogShareUrl}}"
        );
    }

    @Test
    void rendersChannelBodyWithRenderedMessagePlaceholders() throws Exception {
        String rendered = service.renderChannelBody(
                """
                        {"text":"{{message.body}}","payload":{{message.bodyJson}}}
                        """,
                "{\"count\":7}"
        );

        JsonNode json = objectMapper.readTree(rendered);
        assertEquals("{\"count\":7}", json.path("text").asText());
        assertEquals(7, json.path("payload").path("count").asInt());
    }

    @Test
    void rejectsEventPlaceholdersInChannelBody() {
        NotificationTemplateService.TemplateValidationException exception = assertThrows(
                NotificationTemplateService.TemplateValidationException.class,
                () -> service.validateChannelBodyTemplate("{\"title\":\"{{image.ogTitle}}\"}")
        );

        assertEquals("image.ogTitle", exception.placeholders().get(0));
    }

    @Test
    void exposesSchemasForNewWebhookEvents() {
        Set<String> eventTypes = service.events().stream()
                .map(NotificationTemplateService.EventSchema::eventType)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(eventTypes.contains("AI_PROVIDER_REQUEST_FAILED"));
        assertTrue(eventTypes.contains("AI_PROVIDER_AUTO_DOWNGRADED"));
        assertTrue(eventTypes.contains("DATA_CRAWL_REQUEST_FAILED"));
        assertTrue(eventTypes.contains("SHARE_SUMMARY_IMAGE_FAILED"));
        assertTrue(eventTypes.contains("SHARE_SUMMARY_AUDIO_FAILED"));
        assertTrue(service.schema(NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS).placeholderNames().contains("event.key"));
    }

    @Test
    void rendersNewEventPlaceholders() throws Exception {
        String rendered = service.render(
                NotificationEventType.AI_PROVIDER_REQUEST_FAILED,
                """
                        {"event":"{{event.key}}","provider":"{{provider.name}}","count":{{downgrade.failureCount}},"triggered":{{downgrade.triggered}}}
                        """,
                Map.of(
                        "event.key", "AI_PROVIDER_REQUEST_FAILED:1",
                        "provider.name", "OpenAI",
                        "downgrade.failureCount", 2,
                        "downgrade.triggered", false
                )
        );

        JsonNode json = objectMapper.readTree(rendered);
        assertEquals("AI_PROVIDER_REQUEST_FAILED:1", json.path("event").asText());
        assertEquals("OpenAI", json.path("provider").asText());
        assertEquals(2, json.path("count").asInt());
        assertEquals(false, json.path("triggered").asBoolean());
    }

    @Test
    void rejectsCrossEventPlaceholders() {
        NotificationTemplateService.TemplateValidationException exception = assertThrows(
                NotificationTemplateService.TemplateValidationException.class,
                () -> service.validateTemplate(
                        NotificationEventType.DATA_CRAWL_REQUEST_FAILED,
                        "{\"provider\":\"{{provider.name}}\"}"
                )
        );

        assertEquals("provider.name", exception.placeholders().get(0));
    }

    @Test
    void rendersImageFailedPlaceholders() throws Exception {
        String rendered = service.render(
                NotificationEventType.SHARE_SUMMARY_IMAGE_FAILED,
                """
                        {"run":"{{run.taskName}}","image":{{image.id}},"error":"{{error.message}}"}
                        """,
                Map.of(
                        "run.taskName", "每周分享总结",
                        "image.id", 99,
                        "error.message", "IMAGE_QUEUE_FULL"
                )
        );

        JsonNode json = objectMapper.readTree(rendered);
        assertEquals("每周分享总结", json.path("run").asText());
        assertEquals(99, json.path("image").asInt());
        assertEquals("IMAGE_QUEUE_FULL", json.path("error").asText());
    }

    @Test
    void rendersAudioFailedPlaceholders() throws Exception {
        String rendered = service.render(
                NotificationEventType.SHARE_SUMMARY_AUDIO_FAILED,
                """
                        {"run":"{{run.taskName}}","audio":{{audio.id}},"voice":"{{audio.voice}}","error":"{{error.message}}"}
                        """,
                Map.of(
                        "run.taskName", "每周分享总结",
                        "audio.id", 88,
                        "audio.voice", "zh-CN-YunhaoNeural",
                        "error.message", "AUDIO_QUEUE_FULL"
                )
        );

        JsonNode json = objectMapper.readTree(rendered);
        assertEquals("每周分享总结", json.path("run").asText());
        assertEquals(88, json.path("audio").asInt());
        assertEquals("zh-CN-YunhaoNeural", json.path("voice").asText());
        assertEquals("AUDIO_QUEUE_FULL", json.path("error").asText());
    }

    @Test
    void rejectsImagePlaceholdersForAudioFailedEvent() {
        NotificationTemplateService.TemplateValidationException exception = assertThrows(
                NotificationTemplateService.TemplateValidationException.class,
                () -> service.validateTemplate(
                        NotificationEventType.SHARE_SUMMARY_AUDIO_FAILED,
                        "{\"bad\":\"{{image.id}}\"}"
                )
        );

        assertEquals("image.id", exception.placeholders().get(0));
    }
}
