package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.*;
import io.github.shigella520.linkpeek.server.admin.persistence.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static io.github.shigella520.linkpeek.server.admin.service.ShareSummaryVersionStore.Revision;

@Service
public class ShareSummaryRegenerationService {
    private static final Logger log = LoggerFactory.getLogger(ShareSummaryRegenerationService.class);
    private final ShareSummaryVersionStore versions;
    private final ShareSummaryService summaries;
    private final ShareSummaryImageService images;
    private final ShareSummaryAudioService audios;
    private final ShareSummaryMapper runs;
    private final ShareSummaryImageMapper imageMapper;
    private final ShareSummaryAudioMapper audioMapper;
    private final Clock clock;
    private final ExecutorService executor;

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "share-summary-regeneration");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ShareSummaryRegenerationService(ShareSummaryVersionStore versions, ShareSummaryService summaries,
            ShareSummaryImageService images, ShareSummaryAudioService audios, ShareSummaryMapper runs,
            ShareSummaryImageMapper imageMapper, ShareSummaryAudioMapper audioMapper, Clock clock) {
        this(versions, summaries, images, audios, runs, imageMapper, audioMapper, clock, newExecutor());
    }

    ShareSummaryRegenerationService(ShareSummaryVersionStore versions, ShareSummaryService summaries,
            ShareSummaryImageService images, ShareSummaryAudioService audios, ShareSummaryMapper runs,
            ShareSummaryImageMapper imageMapper, ShareSummaryAudioMapper audioMapper, Clock clock, ExecutorService executor) {
        this.executor = executor;
        this.versions = versions;
        this.summaries = summaries;
        this.images = images;
        this.audios = audios;
        this.runs = runs;
        this.imageMapper = imageMapper;
        this.audioMapper = audioMapper;
        this.clock = clock;
    }

    public Revision regenerate(long rootId, String requestKey) {
        String key = requestKey(requestKey);
        return versions.locked(rootId, () -> {
            Revision previous = versions.byRequest(rootId, key);
            if (previous != null) return previous;
            versions.requireIdle(rootId);
            ShareSummaryImageConfigRecord imageConfig = versions.decode(versions.encode(images.readyConfig()), ShareSummaryImageConfigRecord.class);
            ShareSummaryAudioConfigRecord audioConfig = versions.decode(versions.encode(audios.readyConfig()), ShareSummaryAudioConfigRecord.class);
            ShareSummaryRunRecord original = runs.selectRun(rootId);
            ShareSummaryService.PreparedInput input = summaries.prepareRegeneration(original);
            ShareSummaryRunRecord run = summaries.newRevisionRun(original, input);
            imageConfig.setApiKey(null);
            audioConfig.setApiKey(null);
            versions.insert(rootId, run, key, input, imageConfig, audioConfig, images.ogTitle(run), images.ogDescription(run), deadline(input));
            Revision revision = versions.revision(run.getId());
            ShareSummaryVersionStore.afterCommit(() -> submit(revision));
            return revision;
        });
    }

    public Revision retry(long rootId, long runId, String requestKey) {
        String key = requestKey(requestKey);
        return versions.locked(rootId, () -> {
            Revision previous = versions.byRequest(rootId, key);
            if (previous != null) return previous;
            versions.requireIdle(rootId);
            Revision revision = versions.revision(runId);
            if (revision == null || revision.rootRunId() != rootId || versions.latest(rootId).runId() != runId
                    || !List.of("FAILED", "PARTIAL_FAILED").contains(revision.status())) {
                throw new IllegalArgumentException("只能重试最新失败版本的未完成部分。");
            }
            images.readyConfig();
            audios.readyConfig();
            if (imageMapper.selectActiveImage(runId) != null || audioMapper.selectActiveAudio(runId) != null) {
                throw new IllegalStateException("素材仍在生成，请稍后重试。");
            }
            ShareSummaryService.PreparedInput input = versions.decode(revision.inputSnapshot(), ShareSummaryService.PreparedInput.class);
            Revision retry = versions.retry(revision, key, deadline(input));
            ShareSummaryVersionStore.afterCommit(() -> submit(retry));
            return retry;
        });
    }

    private long deadline(ShareSummaryService.PreparedInput input) {
        // Includes queue time, the maximum timeout for each fallback text provider, and both asset stages.
        return clock.millis() + (Math.max(1, input.providers().size()) + 2L) * 3_600_000L;
    }

    private String requestKey(String key) {
        if (key == null || key.isBlank()) return UUID.randomUUID().toString();
        if (key.length() > 128) throw new IllegalArgumentException("请求标识过长。");
        return key;
    }

    private void submit(Revision revision) {
        try { executor.execute(() -> generate(revision)); }
        catch (RejectedExecutionException e) { versions.independent(() -> fail(revision, "生成队列已满，请重试。")); }
    }

    void generate(Revision submitted) {
        try {
            Revision claimed = versions.locked(submitted.rootRunId(), () -> {
                Revision current = versions.revision(submitted.runId());
                if (current == null || current.attempt() != submitted.attempt() || !"QUEUED".equals(current.status())) return null;
                versions.transition(current, "TEXT", null);
                return versions.revision(current.runId());
            });
            if (claimed == null) return;
            ShareSummaryRunRecord run = runs.selectRun(claimed.runId());
            if (!"SUCCESS".equals(run.getStatus())) {
                ShareSummaryService.PreparedInput input = versions.decode(claimed.inputSnapshot(), ShareSummaryService.PreparedInput.class);
                summaries.computeRevision(run, input);
            }
            versions.locked(claimed.rootRunId(), () -> {
                Revision current = versions.revision(claimed.runId());
                if (current.attempt() != claimed.attempt() || !"TEXT".equals(current.status())) return null;
                runs.updateRun(run);
                if ("EMPTY".equals(run.getStatus())) {
                    versions.transition(current, "EMPTY", run.getErrorMessage());
                    return null;
                }
                versions.setAssets(current.runId(), null, null);
                versions.transition(current, "ASSETS", null);
                return null;
            });
            // Keep successful text even if asset admission fails, so retries do not call the text model again.
            versions.locked(claimed.rootRunId(), () -> {
                Revision current = versions.revision(claimed.runId());
                if (current.attempt() != claimed.attempt() || !"ASSETS".equals(current.status())) return null;
                Long imageId = images.generateImageForVersion(run.getId(), false).id();
                Long audioId = audios.generateAudioForVersion(run.getId(), false).id();
                versions.setAssets(run.getId(), imageId, audioId);
                return null;
            });
        } catch (RuntimeException e) {
            fail(submitted, e.getMessage());
        }
    }

    private void fail(Revision submitted, String error) {
        try {
            versions.locked(submitted.rootRunId(), () -> {
                Revision current = versions.revision(submitted.runId());
                if (current != null && current.attempt() == submitted.attempt() && current.active()) {
                    versions.transition(current, "FAILED", error);
                    ShareSummaryRunRecord run = runs.selectRun(current.runId());
                    if ("RUNNING".equals(run.getStatus())) {
                        run.setStatus("FAILED"); run.setErrorMessage(error); run.setFinishedAt(clock.millis());
                        runs.updateRun(run);
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("share_summary_regeneration_failure_save_failed runId={}", submitted.runId(), e);
        }
    }

    @Scheduled(fixedDelay = 2_000L, initialDelay = 2_000L)
    public void reconcile() {
        for (Revision snapshot : versions.active()) {
            try {
                ShareSummaryImageRecord publishedImage = versions.locked(snapshot.rootRunId(), () -> {
                    Revision current = versions.revision(snapshot.runId());
                    if (current == null || !current.active()) return null;
                    if (current.deadline() < clock.millis()) {
                        versions.expireAssets(current.runId());
                        versions.transition(current, "FAILED", "生成超时或服务中断，请重试未完成部分。");
                        ShareSummaryRunRecord run = runs.selectRun(current.runId());
                        if ("RUNNING".equals(run.getStatus())) {
                            run.setStatus("FAILED"); run.setErrorMessage("生成超时或服务中断"); run.setFinishedAt(clock.millis());
                            runs.updateRun(run);
                        }
                        return null;
                    }
                    if ("ASSETS".equals(current.status())) {
                        ShareSummaryImageRecord image = current.imageId() == null ? null : imageMapper.selectImage(current.imageId());
                        ShareSummaryAudioRecord audio = current.audioId() == null ? null : audioMapper.selectAudio(current.audioId());
                        if (versions.publish(current)) {
                            String url = versions.shareUrl(current.rootRunId());
                            if (url != null) image.setOgPageUrl(url);
                            return image;
                        }
                        if (image != null && audio != null && terminal(image.getStatus()) && terminal(audio.getStatus())) {
                            String error = "图片：" + (image == null ? "未生成" : image.getStatus() + " " + nullToEmpty(image.getErrorMessage()))
                                    + "；音频：" + (audio == null ? "未生成" : audio.getStatus() + " " + nullToEmpty(audio.getErrorMessage()));
                            versions.transition(current, "PARTIAL_FAILED", error);
                            return null;
                        }
                    }
                    return null;
                });
                // afterCommit still has transaction-bound connections/sessions. Create the notification only
                // after transaction cleanup, so its insert commits before an asynchronous worker reads it.
                if (publishedImage != null) images.publishImageSuccess(publishedImage);
            } catch (RuntimeException e) {
                log.warn("share_summary_regeneration_reconcile_failed runId={}", snapshot.runId(), e);
            }
        }
    }

    private boolean terminal(String status) { return status == null || !List.of("PENDING", "GENERATING").contains(status); }
    private String nullToEmpty(String value) { return value == null ? "" : value; }

    public List<VersionDetail> history(long rootId) {
        ShareSummaryRunRecord original = runs.selectRun(rootId);
        if (original == null || versions.revision(rootId) != null) throw new IllegalArgumentException("分享总结不存在。");
        long published = versions.publishedRunId(rootId);
        List<VersionDetail> result = new ArrayList<>();
        for (Revision revision : versions.revisions(rootId)) {
            result.add(new VersionDetail(revision, summaries.run(revision.runId()), images.images(revision.runId()),
                    audios.audios(revision.runId()), revision.runId() == published));
        }
        // Base version must read original data, never follow the current publication pointer.
        result.add(new VersionDetail(null, original,
                imageMapper.selectImagesForRun(rootId).stream().map(ShareSummaryImageService.ImageResponse::fromRecord).toList(),
                audioMapper.selectAudiosForRun(rootId).stream().map(audios::response).toList(), published == rootId));
        return result;
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    public record VersionDetail(Revision revision, ShareSummaryRunRecord run,
            List<ShareSummaryImageService.ImageResponse> images, List<ShareSummaryAudioService.AudioResponse> audios,
            boolean published) {}
}
