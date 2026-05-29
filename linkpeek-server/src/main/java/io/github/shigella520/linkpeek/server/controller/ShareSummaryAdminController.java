package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.service.AdminAuthService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
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
@RequestMapping("/api/admin/share-summary")
@Hidden
public class ShareSummaryAdminController {
    private final AdminAuthService adminAuthService;
    private final ShareSummaryService shareSummaryService;

    public ShareSummaryAdminController(AdminAuthService adminAuthService, ShareSummaryService shareSummaryService) {
        this.adminAuthService = adminAuthService;
        this.shareSummaryService = shareSummaryService;
    }

    @GetMapping("/tasks")
    public List<ShareSummaryTaskRecord> tasks(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return shareSummaryService.tasks();
    }

    @PostMapping("/tasks")
    public ShareSummaryTaskRecord createTask(
            HttpServletRequest request,
            @RequestBody ShareSummaryService.TaskRequest taskRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryService.createTask(taskRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PutMapping("/tasks/{taskId}")
    public ShareSummaryTaskRecord updateTask(
            HttpServletRequest request,
            @PathVariable long taskId,
            @RequestBody ShareSummaryService.TaskRequest taskRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryService.updateTask(taskId, taskRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ShareSummaryService.DeleteResponse deleteTask(HttpServletRequest request, @PathVariable long taskId) {
        adminAuthService.requireAuthenticated(request);
        return shareSummaryService.deleteTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/run")
    public ShareSummaryRunRecord runTask(
            HttpServletRequest request,
            @PathVariable long taskId,
            @RequestBody(required = false) String requestBody
    ) {
        adminAuthService.requireAuthenticated(request);
        if (StringUtils.hasText(requestBody)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual window overrides are not supported.");
        }
        try {
            return shareSummaryService.runTask(taskId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/runs")
    public ShareSummaryService.RunPage runs(
            HttpServletRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryService.runs(page, size, taskId, status);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/runs/{runId}")
    public ShareSummaryRunRecord run(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryService.run(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
