package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
