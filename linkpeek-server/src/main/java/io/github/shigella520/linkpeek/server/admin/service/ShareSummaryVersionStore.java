package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.*;
import io.github.shigella520.linkpeek.server.admin.persistence.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** Publication and operation admission share a database write lock, including single-asset operations. */
@Repository
@DependsOn("statisticsSchemaInitializer")
public class ShareSummaryVersionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ShareSummaryMapper runs;
    private final ShareSummaryImageMapper images;
    private final ShareSummaryAudioMapper audios;
    private final Clock clock;

    public ShareSummaryVersionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json,
            ShareSummaryMapper runs, ShareSummaryImageMapper images, ShareSummaryAudioMapper audios, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.runs = runs;
        this.images = images;
        this.audios = audios;
        this.clock = clock;
    }

    public <T> T locked(long rootId, Supplier<T> action) {
        return tx.execute(transaction -> {
            // First statement takes SQLite's write lock before reading admission state.
            jdbc.update("INSERT OR IGNORE INTO share_summary_publication(root_run_id, published_run_id) VALUES (?, ?)", rootId, rootId);
            if (runs.selectRun(rootId) == null || revision(rootId) != null) {
                throw new IllegalArgumentException("分享总结不存在，或该记录是历史版本。请从原总结操作。");
            }
            return action.get();
        });
    }

    public <T> T assetOperation(long rootId, LongFunction<T> action) {
        return locked(rootId, () -> {
            Revision latest = latest(rootId);
            if (latest != null && latest.active()) throw new IllegalStateException("分享总结正在重新生成，请等待完成。");
            return action.apply(publishedRunId(rootId));
        });
    }

    public void requireIdle(long rootId) {
        if (latest(rootId) != null && latest(rootId).active()) {
            throw new IllegalStateException("分享总结正在重新生成，请等待完成。");
        }
        long runId = publishedRunId(rootId);
        if ("RUNNING".equals(runs.selectRun(rootId).getStatus()) || images.selectActiveImage(runId) != null
                || audios.selectActiveAudio(runId) != null) {
            throw new IllegalStateException("分享总结或素材正在生成，请等待完成。");
        }
    }

    public long rootRunId(long runId) {
        Revision revision = revision(runId);
        return revision == null ? runId : revision.rootRunId();
    }

    public long publishedRunId(long rootId) {
        List<Long> ids = jdbc.query("SELECT published_run_id FROM share_summary_publication WHERE root_run_id = ?",
                (rs, row) -> rs.getLong(1), rootId);
        return ids.isEmpty() ? rootId : ids.get(0);
    }

    /** Root reads follow publication; explicit version reads stay on that version. */
    public long displayRunId(long runId) {
        return revision(runId) == null ? publishedRunId(runId) : runId;
    }

    public String shareUrl(long rootId) {
        List<String> urls = jdbc.query("SELECT share_url FROM share_summary_publication WHERE root_run_id = ?",
                (rs, row) -> rs.getString(1), rootId);
        return urls.isEmpty() ? null : urls.get(0);
    }

    public Revision revision(long runId) {
        List<Revision> values = jdbc.query("SELECT * FROM share_summary_revision WHERE run_id = ?", this::revisionRow, runId);
        return values.isEmpty() ? null : values.get(0);
    }

    public Revision latest(long rootId) {
        List<Revision> values = revisions(rootId);
        return values.isEmpty() ? null : values.get(0);
    }

    public List<Revision> revisions(long rootId) {
        return jdbc.query("SELECT * FROM share_summary_revision WHERE root_run_id = ? ORDER BY run_id DESC", this::revisionRow, rootId);
    }

    public List<Revision> active() {
        return jdbc.query("SELECT * FROM share_summary_revision WHERE status IN ('QUEUED','TEXT','ASSETS')", this::revisionRow);
    }

    public Revision byRequest(long rootId, String key) {
        List<Long> ids = jdbc.query("SELECT run_id FROM share_summary_regeneration_request WHERE root_run_id = ? AND request_key = ?",
                (rs, row) -> rs.getLong(1), rootId, key);
        return ids.isEmpty() ? null : revision(ids.get(0));
    }

    public void insert(long rootId, ShareSummaryRunRecord run, String key, Object input,
            ShareSummaryImageConfigRecord imageConfig, ShareSummaryAudioConfigRecord audioConfig,
            String title, String description, long deadline) {
        runs.insertRun(run);
        jdbc.update("""
                INSERT INTO share_summary_revision(run_id, root_run_id, status, request_key, input_snapshot,
                    image_config_snapshot, audio_config_snapshot, title, description, created_at, updated_at, deadline)
                VALUES (?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.getId(), rootId, key, encode(input), encode(imageConfig), encode(audioConfig), title, description,
                clock.millis(), clock.millis(), deadline);
        jdbc.update("INSERT INTO share_summary_regeneration_request(root_run_id, request_key, run_id) VALUES (?, ?, ?)", rootId, key, run.getId());
        ShareSummaryImageRecord oldImage = images.selectLatestSuccessfulImage(publishedRunId(rootId));
        if (oldImage != null) {
            jdbc.update("UPDATE share_summary_publication SET share_url = COALESCE(share_url, ?) WHERE root_run_id = ?",
                    oldImage.getOgPageUrl(), rootId);
        }
    }

    public boolean transition(Revision v, String status, String error) {
        return jdbc.update("""
                UPDATE share_summary_revision SET status = ?, error_message = ?, updated_at = ?
                WHERE run_id = ? AND attempt = ? AND status = ?
                """, status, error, clock.millis(), v.runId(), v.attempt(), v.status()) == 1;
    }

    public void setAssets(long runId, Long imageId, Long audioId) {
        jdbc.update("UPDATE share_summary_revision SET image_id = ?, audio_id = ?, updated_at = ? WHERE run_id = ?",
                imageId, audioId, clock.millis(), runId);
    }

    public Revision retry(Revision v, String key, long deadline) {
        jdbc.update("""
                UPDATE share_summary_revision SET attempt = attempt + 1, status = 'QUEUED', error_message = NULL,
                    updated_at = ?, deadline = ? WHERE run_id = ?
                """, clock.millis(), deadline, v.runId());
        jdbc.update("INSERT INTO share_summary_regeneration_request(root_run_id, request_key, run_id) VALUES (?, ?, ?)", v.rootRunId(), key, v.runId());
        return revision(v.runId());
    }

    public boolean publish(Revision v) {
        ShareSummaryRunRecord run = runs.selectRun(v.runId());
        ShareSummaryImageRecord image = v.imageId() == null ? null : images.selectImage(v.imageId());
        ShareSummaryAudioRecord audio = v.audioId() == null ? null : audios.selectAudio(v.audioId());
        if (!"SUCCESS".equals(run.getStatus()) || image == null || audio == null
                || image.getRunId() != v.runId() || audio.getRunId() != v.runId()
                || !"SUCCESS".equals(image.getStatus()) || !"SUCCESS".equals(audio.getStatus())) {
            return false;
        }
        if (latest(v.rootRunId()).runId() != v.runId() || !transition(v, "SUCCESS", null)) {
            return false;
        }
        jdbc.update("""
                UPDATE share_summary_publication SET published_run_id = ?, share_url = COALESCE(share_url, ?)
                WHERE root_run_id = ?
                """, v.runId(), image.getOgPageUrl(), v.rootRunId());
        return true;
    }

    public void expireAssets(long runId) {
        jdbc.update("UPDATE share_summary_image SET status = 'FAILED', error_message = 'Generation expired', finished_at = ? WHERE run_id = ? AND status IN ('PENDING','GENERATING')", clock.millis(), runId);
        jdbc.update("UPDATE share_summary_audio SET status = 'FAILED', error_message = 'Generation expired', finished_at = ? WHERE run_id = ? AND status IN ('PENDING','GENERATING')", clock.millis(), runId);
    }

    public void deleteMetadata(long rootId) {
        jdbc.update("DELETE FROM share_summary_regeneration_request WHERE root_run_id = ?", rootId);
        jdbc.update("DELETE FROM share_summary_revision WHERE root_run_id = ?", rootId);
        jdbc.update("DELETE FROM share_summary_publication WHERE root_run_id = ?", rootId);
    }

    public ShareSummaryImageConfigRecord imageConfig(long runId, ShareSummaryImageConfigRecord current) {
        Revision v = revision(runId);
        if (v == null || "SUCCESS".equals(v.status())) return current;
        ShareSummaryImageConfigRecord config = decode(v.imageConfigSnapshot(), ShareSummaryImageConfigRecord.class);
        config.setApiKey(current.getApiKey());
        return config;
    }

    public ShareSummaryAudioConfigRecord audioConfig(long runId, ShareSummaryAudioConfigRecord current) {
        Revision v = revision(runId);
        if (v == null || "SUCCESS".equals(v.status())) return current;
        ShareSummaryAudioConfigRecord config = decode(v.audioConfigSnapshot(), ShareSummaryAudioConfigRecord.class);
        config.setApiKey(current.getApiKey());
        return config;
    }

    public void createAudioToken(long audioId) {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        String token = "a_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO share_summary_audio_token(audio_id, public_token) VALUES (?, ?)", audioId, token);
    }

    public String audioToken(long audioId) {
        List<String> tokens = jdbc.query("SELECT public_token FROM share_summary_audio_token WHERE audio_id = ?",
                (rs, row) -> rs.getString(1), audioId);
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    public ShareSummaryAudioRecord audioByToken(String token) {
        List<Long> ids = jdbc.query("SELECT audio_id FROM share_summary_audio_token WHERE public_token = ?",
                (rs, row) -> rs.getLong(1), token);
        return ids.isEmpty() ? null : audios.selectAudio(ids.get(0));
    }

    public void deleteAudioTokens(long runId) {
        jdbc.update("DELETE FROM share_summary_audio_token WHERE audio_id IN (SELECT id FROM share_summary_audio WHERE run_id = ?)", runId);
    }

    public void independent(Runnable action) {
        TransactionTemplate independent = new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> action.run());
    }

    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法保存生成快照", e); }
    }

    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取生成快照", e); }
    }

    private Revision revisionRow(ResultSet rs, int row) throws SQLException {
        return new Revision(rs.getLong("run_id"), rs.getLong("root_run_id"), rs.getString("status"), rs.getInt("attempt"),
                rs.getString("request_key"), rs.getString("input_snapshot"), rs.getString("image_config_snapshot"),
                rs.getString("audio_config_snapshot"), rs.getString("title"), rs.getString("description"),
                nullableLong(rs, "image_id"), nullableLong(rs, "audio_id"), rs.getString("error_message"),
                rs.getLong("created_at"), rs.getLong("updated_at"), rs.getLong("deadline"));
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    public record Revision(long runId, long rootRunId, String status, int attempt, String requestKey,
            @com.fasterxml.jackson.annotation.JsonIgnore String inputSnapshot,
            @com.fasterxml.jackson.annotation.JsonIgnore String imageConfigSnapshot,
            @com.fasterxml.jackson.annotation.JsonIgnore String audioConfigSnapshot,
            String title, String description, Long imageId, Long audioId, String errorMessage,
            long createdAt, long updatedAt, long deadline) {
        public boolean active() { return List.of("QUEUED", "TEXT", "ASSETS").contains(status); }
    }
}
