package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.service.AdminAuthService;
import io.github.shigella520.linkpeek.server.admin.service.NotificationConfigService;
import io.github.shigella520.linkpeek.server.admin.service.NotificationDeleteResponse;
import io.github.shigella520.linkpeek.server.admin.service.NotificationDeliveryService;
import io.github.shigella520.linkpeek.server.admin.service.NotificationTemplateService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/admin/notifications")
@Hidden
public class NotificationAdminController {
    private final AdminAuthService adminAuthService;
    private final NotificationConfigService configService;
    private final NotificationDeliveryService deliveryService;

    public NotificationAdminController(
            AdminAuthService adminAuthService,
            NotificationConfigService configService,
            NotificationDeliveryService deliveryService
    ) {
        this.adminAuthService = adminAuthService;
        this.configService = configService;
        this.deliveryService = deliveryService;
    }

    @GetMapping("/events")
    public List<NotificationTemplateService.EventSchema> events(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return configService.events();
    }

    @GetMapping("/events/{eventType}/placeholders")
    public NotificationTemplateService.EventSchema eventSchema(HttpServletRequest request, @PathVariable String eventType) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.eventSchema(eventType);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/channels")
    public List<NotificationConfigService.ChannelResponse> channels(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return configService.channels();
    }

    @PostMapping("/channels")
    public NotificationConfigService.ChannelResponse createChannel(
            HttpServletRequest request,
            @RequestBody NotificationConfigService.ChannelRequest channelRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.createChannel(channelRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PutMapping("/channels/{channelId}")
    public NotificationConfigService.ChannelResponse updateChannel(
            HttpServletRequest request,
            @PathVariable long channelId,
            @RequestBody NotificationConfigService.ChannelRequest channelRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.updateChannel(channelId, channelRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/channels/{channelId}")
    public NotificationDeleteResponse deleteChannel(HttpServletRequest request, @PathVariable long channelId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.deleteChannel(channelId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/channels/{channelId}/test")
    public NotificationConfigService.TestResponse testChannel(HttpServletRequest request, @PathVariable long channelId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.testChannel(channelId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/tasks")
    public List<NotificationConfigService.TaskResponse> tasks(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return configService.tasks();
    }

    @PostMapping("/tasks")
    public NotificationConfigService.TaskResponse createTask(
            HttpServletRequest request,
            @RequestBody NotificationConfigService.TaskRequest taskRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.createTask(taskRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PutMapping("/tasks/{taskId}")
    public NotificationConfigService.TaskResponse updateTask(
            HttpServletRequest request,
            @PathVariable long taskId,
            @RequestBody NotificationConfigService.TaskRequest taskRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.updateTask(taskId, taskRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public NotificationDeleteResponse deleteTask(HttpServletRequest request, @PathVariable long taskId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.deleteTask(taskId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/tasks/validate-template")
    public NotificationTemplateService.TemplateValidationResult validateTemplate(
            HttpServletRequest request,
            @RequestBody NotificationConfigService.ValidateTemplateRequest validateRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return configService.validateTemplate(validateRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/deliveries")
    public NotificationDeliveryService.DeliveryPage deliveries(
            HttpServletRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String status
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return deliveryService.deliveries(page, size, eventType, taskId, channelId, status);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/deliveries/{deliveryId}")
    public NotificationDeliveryRecord delivery(HttpServletRequest request, @PathVariable long deliveryId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return deliveryService.delivery(deliveryId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/deliveries/{deliveryId}")
    public NotificationDeleteResponse deleteDelivery(HttpServletRequest request, @PathVariable long deliveryId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return deliveryService.deleteDelivery(deliveryId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    public NotificationDeliveryRecord retryDelivery(HttpServletRequest request, @PathVariable long deliveryId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return deliveryService.retryDelivery(deliveryId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
