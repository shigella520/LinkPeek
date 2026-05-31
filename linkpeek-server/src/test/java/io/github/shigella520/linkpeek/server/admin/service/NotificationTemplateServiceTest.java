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
    void rejectsTemplatesThatCannotRenderToJson() {
        assertThrows(
                NotificationTemplateService.TemplateValidationException.class,
                () -> service.validateTemplate(
                        NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS,
                        "{\"count\":{{image.ogTitle}}}"
                )
        );
    }
}
