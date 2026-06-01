package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.service.AdminAuthService;
import io.github.shigella520.linkpeek.server.admin.service.NotificationService;
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
    private final NotificationService notificationService;

    public NotificationAdminController(
            AdminAuthService adminAuthService,
            NotificationService notificationService
    ) {
        this.adminAuthService = adminAuthService;
        this.notificationService = notificationService;
    }

    @GetMapping("/events")
    public List<NotificationTemplateService.EventSchema> events(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return notificationService.events();
    }

    @GetMapping("/events/{eventType}/placeholders")
    public NotificationTemplateService.EventSchema eventSchema(HttpServletRequest request, @PathVariable String eventType) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.eventSchema(eventType);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/channels")
    public List<NotificationService.ChannelResponse> channels(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return notificationService.channels();
    }

    @PostMapping("/channels")
    public NotificationService.ChannelResponse createChannel(
            HttpServletRequest request,
            @RequestBody NotificationService.ChannelRequest channelRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.createChannel(channelRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PutMapping("/channels/{channelId}")
    public NotificationService.ChannelResponse updateChannel(
            HttpServletRequest request,
            @PathVariable long channelId,
            @RequestBody NotificationService.ChannelRequest channelRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.updateChannel(channelId, channelRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/channels/{channelId}")
    public NotificationService.DeleteResponse deleteChannel(HttpServletRequest request, @PathVariable long channelId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.deleteChannel(channelId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/channels/{channelId}/test")
    public NotificationService.TestResponse testChannel(HttpServletRequest request, @PathVariable long channelId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.testChannel(channelId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/tasks")
    public List<NotificationService.TaskResponse> tasks(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return notificationService.tasks();
    }

    @PostMapping("/tasks")
    public NotificationService.TaskResponse createTask(
            HttpServletRequest request,
            @RequestBody NotificationService.TaskRequest taskRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.createTask(taskRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PutMapping("/tasks/{taskId}")
    public NotificationService.TaskResponse updateTask(
            HttpServletRequest request,
            @PathVariable long taskId,
            @RequestBody NotificationService.TaskRequest taskRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.updateTask(taskId, taskRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public NotificationService.DeleteResponse deleteTask(HttpServletRequest request, @PathVariable long taskId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.deleteTask(taskId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/tasks/validate-template")
    public NotificationTemplateService.TemplateValidationResult validateTemplate(
            HttpServletRequest request,
            @RequestBody NotificationService.ValidateTemplateRequest validateRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.validateTemplate(validateRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/deliveries")
    public NotificationService.DeliveryPage deliveries(
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
            return notificationService.deliveries(page, size, eventType, taskId, channelId, status);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/deliveries/{deliveryId}")
    public NotificationDeliveryRecord delivery(HttpServletRequest request, @PathVariable long deliveryId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.delivery(deliveryId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/deliveries/{deliveryId}")
    public NotificationService.DeleteResponse deleteDelivery(HttpServletRequest request, @PathVariable long deliveryId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return notificationService.deleteDelivery(deliveryId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
