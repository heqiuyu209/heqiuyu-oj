package top.hcode.hoj.manager.oj.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Private video originals; ACL checks never depend on the browser's selected context. */
@Component
public class KnowledgeVideoSources {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KnowledgeManager knowledge;
    @Autowired private KnowledgeVideoImports imports;
    @Autowired private KnowledgeVideoAssets assets;
    @Autowired private AiAccessGuard access;
    @Autowired private PlatformTransactionManager transactions;
    private final Semaphore streams = new Semaphore(4);

    public interface Writer {
        void write(String name, String mime, String hash, long length, FileChannel channel, Runnable recheck) throws IOException;
    }

    private boolean administrator(String actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_info u JOIN user_role ur ON ur.uid=u.uuid JOIN role r ON r.id=ur.role_id WHERE u.uuid=? AND u.status=0 AND r.role IN ('root','admin')", Integer.class, actor) > 0;
    }

    private long number(Object value) { return ((Number) value).longValue(); }
    private ObjectiveException unavailable() { return new ObjectiveException(404, "视频不存在或已撤回"); }
    private Map<String,Object> document(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new ObjectiveException(400, "视频资料标识无效");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT state,visibility,published_version,revision FROM knowledge_document WHERE id=?", id);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("state"))) throw unavailable();
        return rows.get(0);
    }

    private Map<String,Object> load(String id, int version) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT s.*,v.content,v.content_hash,v.format,v.state AS version_state FROM knowledge_video_source s JOIN knowledge_version v ON v.document_id=s.document_id AND v.version=s.version WHERE s.document_id=? AND s.version=?", id, version);
        if (rows.isEmpty() || !"video".equals(rows.get(0).get("format")) || "DELETED".equals(rows.get(0).get("version_state"))) throw unavailable();
        Map<String,Object> row = rows.get(0);
        try {
            if (!Arrays.asList("ASR", "ASR_REVIEWED").contains(row.get("extraction_mode"))
                    || !Arrays.asList("video/mp4", "video/webm").contains(row.get("mime"))
                    || !RagServiceClient.hash((String) row.get("content")).equals(row.get("content_hash"))
                    || VideoTranscript.parse((String) row.get("content")).durationMs != number(row.get("duration_ms"))) throw new IllegalArgumentException();
            KnowledgeVideoAssets.sourceName((String) row.get("source_name"));
        } catch (Exception error) { throw new ObjectiveException(503, "视频版本信息校验失败，请联系管理员"); }
        return row;
    }

    private void scope(String actor, String id, int version, long revision, boolean maintenance) {
        access.requireChat(actor);
        Map<String,Object> current = document(id);
        if (number(current.get("revision")) != revision) throw new ObjectiveException(409, "资料已更新，请重新打开视频");
        if (maintenance) {
            if (!administrator(actor)) throw new ObjectiveException(403, "当前账号不能维护视频资料");
        } else if (current.get("published_version") == null || number(current.get("published_version")) != version
                || !"PUBLIC".equals(current.get("visibility")) && !administrator(actor)) throw unavailable();
    }

    public String userAccess(String id, int version) {
        String actor = knowledge.uid();
        if (version < 1) throw new ObjectiveException(400, "视频版本无效");
        Map<String,Object> visible = knowledge.read(id, version);
        if (!"video".equals(visible.get("format"))) throw unavailable();
        Map<String,Object> current = document(id); scope(actor, id, version, number(current.get("revision")), false);
        return actor;
    }

    public String adminAccess(String id, int version) {
        String actor = imports.currentAdmin(); access.requireChat(actor);
        document(id); load(id, version); return actor;
    }

    public String importAccess(String id) {
        String actor = imports.currentAdmin(); access.requireChat(actor); imports.preview(id); return actor;
    }

    private void send(Map<String,Object> source, Runnable check, Writer writer) throws IOException {
        if (!streams.tryAcquire()) throw new ObjectiveException(429, "视频播放繁忙，请稍后重试");
        try (FileChannel channel = assets.open((String) source.get("asset_id"), (String) source.get("source_hash"), number(source.get("byte_length")))) {
            check.run();
            writer.write((String) source.get("source_name"), (String) source.get("mime"), (String) source.get("source_hash"), number(source.get("byte_length")), channel, check);
        } finally { streams.release(); }
    }

    public void userOriginal(String id, int version, Writer writer) throws IOException {
        String actor = userAccess(id, version); Map<String,Object> current = document(id);
        long revision = number(current.get("revision"));
        send(load(id, version), () -> scope(actor, id, version, revision, false), writer);
    }

    public void adminOriginal(String id, int version, Writer writer) throws IOException {
        String actor = adminAccess(id, version); long revision = number(document(id).get("revision"));
        send(load(id, version), () -> scope(actor, id, version, revision, true), writer);
    }

    public void importOriginal(String id, Writer writer) throws IOException {
        String actor = importAccess(id); Map<String,Object> source = imports.preview(id); long revision = number(source.get("revision"));
        send(source, () -> {
            access.requireChat(actor); imports.currentAdmin();
            Map<String,Object> current = jdbc.queryForMap("SELECT state,revision FROM knowledge_video_import WHERE id=?", id);
            if (!"READY".equals(current.get("state")) || number(current.get("revision")) != revision)
                throw new ObjectiveException(409, "转写草稿已更新，请重新打开视频");
        }, writer);
    }

    /** Metadata edits preserve the server original; only an explicit review may replace captions. */
    public Map<String,Object> reuse(String id, KnowledgeDTO.Upload metadata) {
        imports.currentAdmin(); knowledge.validateMetadata(metadata);
        document(id);
        if (metadata.getSourceVersion() == null || metadata.getSourceVersion() < 1) throw new ObjectiveException(400, "请选择视频原件版本");
        Map<String,Object> source = load(id, metadata.getSourceVersion());
        VideoTranscript transcript;
        try { transcript = VideoTranscript.parse(metadata.isReviewedVideo() ? metadata.getContent() : (String) source.get("content")); }
        catch (IllegalArgumentException error) { throw new ObjectiveException(400, "校订正文或视频时间轴无效"); }
        KnowledgeVideoUpload upload = new KnowledgeVideoUpload(metadata, source, transcript,
                metadata.isReviewedVideo() || "ASR_REVIEWED".equals(source.get("extraction_mode")));
        try (FileChannel ignored = assets.open(upload.assetId, upload.sourceHash, upload.byteLength)) {
            return new TransactionTemplate(transactions).execute(status -> { imports.currentAdmin(); return knowledge.upload(id, upload); });
        } catch (IOException error) { throw new ObjectiveException(503, "视频原件校验失败"); }
    }
}
