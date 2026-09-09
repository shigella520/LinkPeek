package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.service.AdminAuthService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryAudioService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryImageService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final ShareSummaryImageService shareSummaryImageService;
    private final ShareSummaryAudioService shareSummaryAudioService;

    public ShareSummaryAdminController(
            AdminAuthService adminAuthService,
            ShareSummaryService shareSummaryService,
            ShareSummaryImageService shareSummaryImageService,
            ShareSummaryAudioService shareSummaryAudioService
    ) {
        this.adminAuthService = adminAuthService;
        this.shareSummaryService = shareSummaryService;
        this.shareSummaryImageService = shareSummaryImageService;
        this.shareSummaryAudioService = shareSummaryAudioService;
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
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryService.runs(page, size, taskId, status, triggerType);
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

    @DeleteMapping("/runs/{runId}")
    public ShareSummaryService.DeleteRunResponse deleteRun(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryService.deleteRun(runId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/image-config")
    public ShareSummaryImageService.ConfigResponse imageConfig(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return shareSummaryImageService.config();
    }

    @PutMapping("/image-config")
    public ShareSummaryImageService.ConfigResponse updateImageConfig(
            HttpServletRequest request,
            @RequestBody ShareSummaryImageService.ConfigRequest imageConfigRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryImageService.updateConfig(imageConfigRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/image-config/test")
    public ShareSummaryImageService.ConfigResponse testImageConfig(
            HttpServletRequest request,
            @RequestBody ShareSummaryImageService.ConfigRequest imageConfigRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryImageService.testConfig(imageConfigRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/audio-config")
    public ShareSummaryAudioService.ConfigResponse audioConfig(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return shareSummaryAudioService.config();
    }

    @PutMapping("/audio-config")
    public ShareSummaryAudioService.ConfigResponse updateAudioConfig(
            HttpServletRequest request,
            @RequestBody ShareSummaryAudioService.ConfigRequest audioConfigRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryAudioService.updateConfig(audioConfigRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/audio-config/test")
    public ShareSummaryAudioService.TestResponse testAudioConfig(
            HttpServletRequest request,
            @RequestBody ShareSummaryAudioService.ConfigRequest audioConfigRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryAudioService.testConfig(audioConfigRequest);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/audio-config/test-audio.{ext}")
    public ResponseEntity<Resource> testAudio(HttpServletRequest request, @PathVariable String ext) {
        adminAuthService.requireAuthenticated(request);
        try {
            ShareSummaryAudioService.TestAudio audio = shareSummaryAudioService.testAudio(ext);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(audio.mediaType())
                    .body(audio.resource());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/runs/{runId}/image")
    public ShareSummaryImageService.ImageResponse generateImage(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryImageService.generateImage(runId, false);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/runs/{runId}/image/regenerate")
    public ShareSummaryImageService.ImageResponse regenerateImage(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryImageService.generateImage(runId, true);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/runs/{runId}/images")
    public List<ShareSummaryImageService.ImageResponse> images(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryImageService.images(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/images/{imageId}")
    public ShareSummaryImageService.ImageResponse image(HttpServletRequest request, @PathVariable long imageId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryImageService.image(imageId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/runs/{runId}/audio")
    public ShareSummaryAudioService.AudioResponse generateAudio(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryAudioService.generateAudio(runId, false);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/runs/{runId}/audio/regenerate")
    public ShareSummaryAudioService.AudioResponse regenerateAudio(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryAudioService.generateAudio(runId, true);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/runs/{runId}/audios")
    public List<ShareSummaryAudioService.AudioResponse> audios(HttpServletRequest request, @PathVariable long runId) {
        adminAuthService.requireAuthenticated(request);
        try {
            return shareSummaryAudioService.audios(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
