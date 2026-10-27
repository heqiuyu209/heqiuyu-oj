package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Queue/state tests. These do not establish MySQL locking or filesystem safety. */
class KnowledgeVideoImportsTest {
    private JdbcTemplate jdbc;
    private KnowledgeVideoImports imports;
    private KnowledgeManager knowledge;
    private KnowledgeJobManager jobs;
    private RagServiceClient processor;
    private KnowledgeVideoAssets assets;
    private KnowledgeVideoSources sources;
    private AiAccessGuard access;
    private AtomicLong clock;
    private String actor;
    private int nonce;
    private static final String SOURCE_HASH = String.join("", Collections.nCopies(64, "a"));
    private static final String MODEL_HASH = String.join("", Collections.nCopies(64, "b"));

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:video_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.execute("CREATE TABLE role(id INT PRIMARY KEY,role VARCHAR(32))");
        jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id INT,PRIMARY KEY(uid,role_id))");
        jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0),('carol',0),('dave',0)");
        jdbc.update("INSERT INTO role VALUES(1,'root'),(2,'admin')");
        jdbc.update("INSERT INTO user_role VALUES('alice',1),('bob',2),('carol',2),('dave',2)");
        for (String file : Arrays.asList("knowledge-base.sql", "knowledge-video.sql")) {
            String schema = StreamUtils.copyToString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)
                    .replace(" COLLATE=utf8mb4_bin", "");
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
            }
        }
        actor = "alice";
        clock = new AtomicLong(1000000L);
        processor = mock(RagServiceClient.class);
        assets = mock(KnowledgeVideoAssets.class);
        when(processor.configured()).thenReturn(true);
        when(assets.configured()).thenReturn(true);
        when(processor.ingest(any())).thenReturn(1);
        knowledge = new KnowledgeManager() {
            @Override protected String uid() { return actor; }
            @Override protected boolean maintainer() { return true; }
            @Override protected long now() { return clock.get(); }
        };
        imports = new KnowledgeVideoImports() { @Override protected long now() { return clock.get(); } };
        jobs = new KnowledgeJobManager() { @Override protected long now() { return clock.get(); } };
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        for (Object bean : Arrays.asList(knowledge, imports, jobs)) {
            ReflectionTestUtils.setField(bean, "jdbc", jdbc);
            ReflectionTestUtils.setField(bean, "transactions", transactions);
        }
        ReflectionTestUtils.setField(knowledge, "index", processor);
        access = mock(AiAccessGuard.class);
        ReflectionTestUtils.setField(knowledge, "access", access);
        ReflectionTestUtils.setField(imports, "json", new ObjectMapper());
        ReflectionTestUtils.setField(imports, "knowledge", knowledge);
        ReflectionTestUtils.setField(imports, "assets", assets);
        ReflectionTestUtils.setField(imports, "processor", processor);
        ReflectionTestUtils.setField(jobs, "index", processor);
        sources = new KnowledgeVideoSources();
        ReflectionTestUtils.setField(sources, "jdbc", jdbc);
        ReflectionTestUtils.setField(sources, "knowledge", knowledge);
        ReflectionTestUtils.setField(sources, "imports", imports);
        ReflectionTestUtils.setField(sources, "assets", assets);
        ReflectionTestUtils.setField(sources, "access", access);
        ReflectionTestUtils.setField(sources, "transactions", transactions);
    }

    @AfterEach void close() { imports.close(); jobs.close(); }

    private KnowledgeDTO.Upload metadata() {
        KnowledgeDTO.Upload result = new KnowledgeDTO.Upload();
        result.setRequestId("video_request_" + (++nonce));
        result.setTitle("前缀和视频");
        result.setVisibility("PUBLIC");
        return result;
    }

    private KnowledgeVideoDTO.Asset asset() {
        KnowledgeVideoDTO.Asset result = new KnowledgeVideoDTO.Asset();
        result.setAssetId(UUID.randomUUID().toString().replace("-", ""));
        result.setSourceHash(SOURCE_HASH);
        result.setByteLength(100L);
        return result;
    }

    private String register() {
        return (String) imports.register(actor, null, metadata(), "内部讲义.mp4", "auto", asset()).get("id");
    }

    private Map<String,Object> row(String id) { return jdbc.queryForMap("SELECT * FROM knowledge_video_import WHERE id=?", id); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private int quota(String owner) { return jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner=?", Integer.class, owner); }
    private void fail(int status, Runnable action) { assertEquals(status, assertThrows(ObjectiveException.class, action::run).getStatus()); }

    private KnowledgeVideoDTO.Change change(String id) {
        KnowledgeVideoDTO.Change result = new KnowledgeVideoDTO.Change();
        result.setExpectedRevision(number(row(id).get("revision")));
        return result;
    }

    private KnowledgeVideoDTO.Probe probe(Map<String,Object> job) {
        KnowledgeVideoDTO.Probe result = new KnowledgeVideoDTO.Probe();
        result.setAssetId((String) job.get("asset_id"));
        result.setSourceHash((String) job.get("source_hash"));
        result.setByteLength(number(job.get("byte_length")));
        result.setDurationMs(80000L);
        result.setMime("video/mp4");
        result.setWidth(640); result.setHeight(360);
        result.setVideoCodec("h264"); result.setAudioCodec("aac"); result.setAudioIndex(1);
        return result;
    }

    private KnowledgeVideoDTO.Transcribed part(Map<String,Object> job, boolean speech) {
        KnowledgeVideoDTO.Transcribed result = new KnowledgeVideoDTO.Transcribed();
        result.setAssetId((String) job.get("asset_id")); result.setSourceHash((String) job.get("source_hash"));
        result.setByteLength(number(job.get("byte_length")));
        long start = number(job.get("next_ms")), duration = number(job.get("duration_ms"));
        result.setStartMs(start); result.setEndMs(Math.min(start + 30000L, duration)); result.setDurationMs(duration);
        result.setModelHash(MODEL_HASH); result.setEngine("faster-whisper-cpu-int8-v1"); result.setLanguage("zh");
        result.setSegments(new ArrayList<>());
        if (speech) {
            KnowledgeVideoDTO.Speech value = new KnowledgeVideoDTO.Speech();
            value.setStartMs(start + 1000L); value.setEndMs(start + 5000L);
            value.setText("前缀和是\"区间和\"。\n保留原始识别。"); result.getSegments().add(value);
        }
        return result;
    }

    private void ready(String id) {
        Map<String,Object> job = imports.claim(); assertEquals(id, job.get("id")); imports.probed(job, probe(job));
        while (!"READY".equals(row(id).get("state"))) {
            job = imports.claim(); assertEquals(id, job.get("id")); imports.transcribed(job, part(job, true));
        }
    }

    private KnowledgeVideoDTO.Save saveMetadata(String id) {
        KnowledgeVideoDTO.Save result = new KnowledgeVideoDTO.Save();
        result.setRequestId("video_save_" + (++nonce)); result.setTitle("前缀和视频"); result.setVisibility("PUBLIC");
        result.setImportRevision(number(row(id).get("revision"))); return result;
    }

    @Test void duplicateUploadDoesNotReserveAgainOrReuseNewPrivateAsset() {
        KnowledgeDTO.Upload metadata = metadata(); KnowledgeVideoDTO.Asset original = asset();
        Map<String,Object> first = imports.register(actor, null, metadata, "讲义.mp4", "auto", original);
        Map<String,Object> replay = imports.register(actor, null, metadata, "讲义.mp4", "auto", asset());
        assertEquals(first.get("id"), replay.get("id")); assertEquals(false, replay.get("newSource"));
        assertEquals(original.getAssetId(), row((String) first.get("id")).get("asset_id"));
        assertEquals(1, quota("_all")); assertEquals(1, quota(actor));
        metadata.setTitle("其他题名"); fail(409, () -> imports.register(actor, null, metadata, "讲义.mp4", "auto", asset()));
        assertEquals(1, quota("_all"));
    }

    @Test void activeDraftCapsAreGlobalAndPerOriginalUploaderUntilSavedOrCancelled() {
        String first = register(); register(); register(); register(); fail(429, this::register);
        assertEquals(4, quota(actor));
        imports.cancel(first, change(first)); assertEquals(3, quota(actor));
        register(); assertEquals(4, quota(actor));
        actor = "bob"; for (int i = 0; i < 4; i++) register();
        actor = "carol"; for (int i = 0; i < 4; i++) register();
        actor = "dave"; fail(429, this::register); assertEquals(12, quota("_all"));
    }

    @Test void processorMisconfigurationLeavesWorkDurableAndInactive() {
        String id = register(); when(processor.configured()).thenReturn(false);
        assertFalse(imports.processOne()); assertEquals("QUEUED", row(id).get("state"));
        assertEquals(0L, number(row(id).get("attempts"))); assertEquals(false, imports.list().get("configured"));
    }

    @Test void expiredLeasesAndDuplicateCallbacksCannotAppendOrAdvance() {
        String id = register(); Map<String,Object> old = imports.claim();
        clock.addAndGet(180000L); imports.probed(old, probe(old)); assertEquals("PROBE", row(id).get("stage"));
        Map<String,Object> current = imports.claim(); assertNotEquals(old.get("lease_token"), current.get("lease_token"));
        imports.probed(old, probe(old)); imports.failed(old, "VIDEO_ASR_FAILED", false);
        assertEquals("RUNNING", row(id).get("state")); imports.probed(current, probe(current));
        Map<String,Object> slice = imports.claim(); KnowledgeVideoDTO.Transcribed result = part(slice, true);
        imports.transcribed(slice, result); imports.transcribed(slice, result);
        assertEquals(30000L, number(row(id).get("next_ms")));
        assertEquals(1, VideoTranscript.parse(newTimeline(row(id))).segments.size());
    }

    private String newTimeline(Map<String,Object> row) {
        return "{\"schema\":1,\"durationMs\":" + row.get("duration_ms") + ",\"segments\":" + row.get("segments") + "}";
    }

    @Test void resumedCheckpointRetainsSpeechAndPinsModelWithoutHoldingTransactionOverRpc() {
        String id = register();
        when(processor.probeVideo(any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return probe(row(id));
        });
        when(processor.transcribeVideo(any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            KnowledgeVideoDTO.Slice request = call.getArgument(0);
            if (request.getStartMs() == 0) assertNull(request.getExpectedModelHash());
            else assertEquals(MODEL_HASH, request.getExpectedModelHash());
            return part(row(id), request.getStartMs() != 30000L);
        });
        for (int i = 0; i < 4; i++) assertTrue(imports.processOne());
        assertFalse(imports.processOne()); assertEquals("READY", row(id).get("state"));
        assertEquals(80000L, number(row(id).get("next_ms")));
        assertEquals(2, VideoTranscript.parse((String) imports.detail(id).get("content")).segments.size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class));
    }

    @Test void modelChangeRequiresFullRestartAndNeverMixesTwoModels() {
        String id = register(); Map<String,Object> job = imports.claim(); imports.probed(job, probe(job));
        job = imports.claim(); imports.transcribed(job, part(job, true)); Map<String,Object> second = imports.claim();
        KnowledgeVideoDTO.Transcribed changed = part(second, true); changed.setModelHash(SOURCE_HASH);
        fail(409, () -> imports.transcribed(second, changed)); imports.failed(second, "VIDEO_MODEL_CHANGED", false);
        assertEquals(30000L, number(row(id).get("next_ms"))); fail(409, () -> imports.retry(id, change(id)));
        KnowledgeVideoDTO.Change restart = change(id); restart.setRestart(true); imports.retry(id, restart);
        assertEquals("PROBE", row(id).get("stage")); assertNull(row(id).get("model_hash"));
        assertEquals("[]", row(id).get("segments")); assertEquals(0L, number(row(id).get("next_ms")));
        assertEquals(1, quota(actor));
    }

    @Test void mismatchedSourceAndSkippedCheckpointAreRejectedWithoutMutation() {
        String id = register(); Map<String,Object> first = imports.claim(); KnowledgeVideoDTO.Probe wrong = probe(first);
        wrong.setSourceHash(MODEL_HASH); fail(502, () -> imports.probed(first, wrong));
        imports.probed(first, probe(first)); Map<String,Object> slice = imports.claim();
        KnowledgeVideoDTO.Transcribed skipped = part(slice, true); skipped.setEndMs(60000L);
        fail(502, () -> imports.transcribed(slice, skipped)); assertEquals(0L, number(row(id).get("next_ms")));
        KnowledgeVideoDTO.Transcribed outside = part(slice, true); outside.getSegments().get(0).setStartMs(-1L);
        fail(502, () -> imports.transcribed(slice, outside));
    }

    @Test void repeatedServiceFailureHasBoundedAttemptsAndRetryResumesLastCheckpoint() {
        String id = register(); Map<String,Object> job = imports.claim(); imports.probed(job, probe(job));
        job = imports.claim(); imports.transcribed(job, part(job, true));
        for (int i = 0; i < 3; i++) {
            Map<String,Object> attempt = imports.claim(); assertNotNull(attempt);
            imports.failed(attempt, "VIDEO_ASR_FAILED", true);
            assertNull(imports.claim()); clock.addAndGet(60001L);
        }
        assertEquals("FAILED", row(id).get("state")); assertEquals(30000L, number(row(id).get("next_ms")));
        imports.retry(id, change(id)); Map<String,Object> resumed = imports.claim();
        assertEquals(30000L, number(resumed.get("next_ms"))); assertEquals(MODEL_HASH, resumed.get("model_hash"));
        assertEquals(1L, number(resumed.get("stage_attempts"))); assertEquals(1, quota(actor));
    }

    @Test void repeatedWorkerCrashesDoNotConsumeUnlimitedAutomaticAttempts() {
        String id = register();
        for (int i = 0; i < 3; i++) { assertNotNull(imports.claim()); clock.addAndGet(180001L); }
        assertTrue(imports.processOne()); assertEquals("FAILED", row(id).get("state"));
        verify(processor, never()).probeVideo(any());
    }

    @Test void providerDetailsCannotBePersistedAsUserVisibleFailure() {
        String id = register(); Map<String,Object> job = imports.claim();
        imports.failed(job, "private filename and provider credentials", false);
        assertEquals("VIDEO_SERVICE_UNAVAILABLE", row(id).get("last_error"));
    }

    @Test void noSpeechFinishesAsActionableFailureWithoutCreatingDocument() {
        String id = register(); Map<String,Object> job = imports.claim(); imports.probed(job, probe(job));
        for (int i = 0; i < 3; i++) { job = imports.claim(); imports.transcribed(job, part(job, false)); }
        assertEquals("FAILED", row(id).get("state")); assertEquals("VIDEO_NO_SPEECH", row(id).get("last_error"));
        fail(409, () -> imports.retry(id, change(id))); assertEquals(1, quota("_all"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class));
    }

    @Test void revokedUploaderCannotCompleteButAnotherCurrentAdminCanResume() {
        String id = register(); Map<String,Object> job = imports.claim(); jdbc.update("DELETE FROM user_role WHERE uid='alice'");
        imports.probed(job, probe(job)); assertEquals("FAILED", row(id).get("state"));
        assertEquals("ACCESS_REVOKED", row(id).get("last_error"));
        fail(403, () -> imports.retry(id, change(id))); actor = "bob"; imports.retry(id, change(id));
        assertEquals("alice", row(id).get("requested_by")); assertEquals("bob", row(id).get("processing_by"));
        job = imports.claim(); imports.probed(job, probe(job)); assertEquals("TRANSCRIBE", row(id).get("stage"));
        assertEquals(1, quota("alice")); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_video_quota WHERE owner='bob'", Integer.class));
    }

    @Test void cancellationIsIdempotentFencesLateWritesAndReleasesQuotaOnce() {
        String id = register(); Map<String,Object> lease = imports.claim(); KnowledgeVideoDTO.Change change = change(id);
        imports.cancel(id, change); imports.cancel(id, change); imports.probed(lease, probe(lease));
        assertEquals("CANCELLED", row(id).get("state")); assertNull(row(id).get("duration_ms"));
        assertEquals(0, quota("_all")); assertEquals(0, quota(actor)); verify(assets, times(2)).discard((String) lease.get("asset_id"));
        fail(409, () -> imports.retry(id, change(id)));
    }

    @Test void staleRevisionCannotCancelAndFailedCounterReleaseRollsBack() {
        String id = register(); KnowledgeVideoDTO.Change stale = change(id); imports.claim();
        fail(409, () -> imports.cancel(id, stale)); assertEquals(1, quota("_all"));
        jdbc.update("UPDATE knowledge_video_quota SET active_count=0 WHERE owner='alice'");
        fail(503, () -> imports.cancel(id, change(id))); assertEquals("RUNNING", row(id).get("state"));
        assertEquals(1, quota("_all")); verify(assets, never()).discard(anyString());
    }

    @Test void saveIsAtomicIdempotentAndDoesNotPublishOrTrustUnreviewedBrowserText() {
        String id = register(); ready(id); KnowledgeVideoDTO.Save metadata = saveMetadata(id); metadata.setContent("浏览器伪造正文");
        Map<String,Object> saved = imports.save(id, metadata); String document = (String) saved.get("id");
        assertEquals("SAVED", row(id).get("state")); assertEquals(0, quota("_all")); assertEquals(0, quota(actor));
        assertNull(knowledge.detail(document).get("publishedVersion"));
        Map<String,Object> original = knowledge.adminVersion(document, 1);
        assertTrue(((String) original.get("content")).contains("前缀和")); assertFalse(((String) original.get("content")).contains("伪造"));
        assertEquals("ASR", ((Map<?,?>) original.get("source")).get("extractionMode"));
        assertEquals(document, imports.save(id, metadata).get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version", Integer.class));
        metadata.setTitle("另一份讲义"); fail(409, () -> imports.save(id, metadata));
        fail(409, () -> imports.cancel(id, change(id))); verify(assets, never()).discard(anyString());
    }

    @Test void failedSaveRollsBackSourceVersionAndQuotaAndAllowsRetry() {
        String id = register(); ready(id); KnowledgeVideoDTO.Save metadata = saveMetadata(id);
        jdbc.execute("ALTER TABLE knowledge_job ADD CONSTRAINT reject_video_ingest CHECK(operation<>'INGEST')");
        assertThrows(RuntimeException.class, () -> imports.save(id, metadata));
        assertEquals("READY", row(id).get("state")); assertEquals(1, quota("_all"));
        for (String table : Arrays.asList("knowledge_document", "knowledge_version", "knowledge_video_source", "knowledge_command"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        jdbc.execute("ALTER TABLE knowledge_job DROP CONSTRAINT reject_video_ingest"); imports.save(id, metadata);
        assertEquals("SAVED", row(id).get("state")); assertEquals(0, quota("_all"));
    }

    @Test void reviewedTranscriptHasNewImmutableSnapshotAndCannotChangeVideoDuration() {
        String id = register(); ready(id); String raw = (String) imports.detail(id).get("content");
        KnowledgeVideoDTO.Save metadata = saveMetadata(id); metadata.setReviewedVideo(true);
        metadata.setContent(raw.replace("80000", "80001")); fail(400, () -> imports.save(id, metadata));
        metadata.setContent(raw.replace("保留原始识别", "管理员校订")); Map<String,Object> saved = imports.save(id, metadata);
        Map<String,Object> version = knowledge.adminVersion((String) saved.get("id"), 1);
        assertTrue(((String) version.get("content")).contains("管理员校订"));
        assertEquals("ASR_REVIEWED", ((Map<?,?>) version.get("source")).get("extractionMode"));
        assertEquals(raw, imports.detail(id).get("content")); assertNull(saved.get("publishedVersion"));
    }

    @Test void staleTargetDoesNotConsumeDraftAndAdministratorMayExplicitlySaveSeparately() {
        KnowledgeDTO.Upload original = metadata(); original.setFormat("markdown"); original.setContent("原讲义");
        String document = (String) knowledge.create(original).get("id"); jobs.processOne();
        KnowledgeDTO.Change publish = new KnowledgeDTO.Change(); publish.setVersion(1); publish.setExpectedRevision(1L);
        knowledge.publish(document, publish);
        KnowledgeDTO.Upload uploaded = metadata(); uploaded.setExpectedRevision(number(knowledge.detail(document).get("revision")));
        String id = (String) imports.register(actor, document, uploaded, "新视频.mp4", "auto", asset()).get("id"); ready(id);
        KnowledgeDTO.Upload newer = metadata(); newer.setFormat("text"); newer.setContent("更新的讲义"); newer.setExpectedRevision(uploaded.getExpectedRevision()); knowledge.upload(document, newer);
        KnowledgeVideoDTO.Save metadata = saveMetadata(id); metadata.setExpectedRevision(uploaded.getExpectedRevision());
        fail(409, () -> imports.save(id, metadata)); assertEquals("READY", row(id).get("state")); assertEquals(1, quota("_all"));
        assertEquals(1L, number(knowledge.detail(document).get("publishedVersion")));
        metadata.setAsNew(true); Map<String,Object> saved = imports.save(id, metadata);
        assertNotEquals(document, saved.get("id")); assertNull(saved.get("publishedVersion"));
        assertEquals(1L, number(knowledge.detail(document).get("publishedVersion")));
    }

    private String savedVideo() {
        String id = register(); ready(id); return (String) imports.save(id, saveMetadata(id)).get("id");
    }

    private void publish(String id, int version) {
        while (jobs.processOne()) { /* Complete actual durable ingest jobs with controlled processor. */ }
        KnowledgeDTO.Change change = new KnowledgeDTO.Change(); change.setVersion(version);
        change.setExpectedRevision(number(knowledge.detail(id).get("revision"))); knowledge.publish(id, change);
    }

    @Test void originalPlaybackUsesCurrentPublicationAndActualRoleInsteadOfCachedRole() throws Exception {
        String id = savedVideo(); fail(404, () -> sources.userAccess(id, 1));
        assertEquals(actor, sources.adminAccess(id, 1)); publish(id, 1); assertEquals(actor, sources.userAccess(id, 1));
        jdbc.update("UPDATE knowledge_document SET visibility='ADMIN',revision=revision+1 WHERE id=?", id);
        jdbc.update("DELETE FROM user_role WHERE uid='bob'"); actor = "bob";
        // KnowledgeManager's fixture deliberately still reports maintainer=true (a stale role cache).
        fail(404, () -> sources.userAccess(id, 1)); fail(403, () -> sources.adminAccess(id, 1));
        actor = "alice"; jdbc.update("UPDATE knowledge_document SET state='DELETED',revision=revision+1 WHERE id=?", id);
        fail(404, () -> sources.adminAccess(id, 1)); fail(404, () -> sources.userAccess(id, 1));
    }

    @Test void originalIsRecheckedAfterFileOpenAndDuringPlayback() throws Exception {
        String id = savedVideo(); publish(id, 1);
        sources.userOriginal(id, 1, (name, mime, hash, length, channel, check) -> {
            assertEquals("video/mp4", mime); assertEquals(100L, length); assertEquals(SOURCE_HASH, hash);
            jdbc.update("UPDATE knowledge_document SET published_version=NULL,revision=revision+1 WHERE id=?", id);
            fail(409, check);
        });
        publish(id, 1);
        when(assets.open(anyString(), anyString(), anyLong())).thenAnswer(call -> {
            doThrow(new ObjectiveException(403, "contest started")).when(access).requireChat(actor); return null;
        });
        assertEquals(403, assertThrows(ObjectiveException.class, () -> sources.userOriginal(id, 1, (a,b,c,d,e,f) -> org.junit.jupiter.api.Assertions.fail("must not write revoked media"))).getStatus());
    }

    @Test void draftPlaybackStopsAfterSavingAndCannotBypassDocumentDeletion() throws Exception {
        String id = register(); fail(409, () -> sources.importAccess(id)); ready(id);
        sources.importOriginal(id, (name, mime, hash, length, channel, check) -> {
            imports.save(id, saveMetadata(id)); fail(409, check);
        });
        fail(409, () -> sources.importAccess(id));
    }

    @Test void metadataEditsReuseOriginalAndReviewedSnapshotsRemainImmutableAndIdempotent() {
        String id = savedVideo(); String original = (String) knowledge.adminVersion(id, 1).get("content");
        KnowledgeDTO.Upload edit = metadata(); edit.setTitle("视频新题名"); edit.setFormat("video"); edit.setSourceVersion(1);
        edit.setExpectedRevision(number(knowledge.detail(id).get("revision"))); edit.setContent("forged browser text");
        Map<String,Object> result = sources.reuse(id, edit); assertEquals(2L, number(result.get("latestVersion")));
        assertEquals(original, knowledge.adminVersion(id, 2).get("content"));
        assertEquals(2L, number(sources.reuse(id, edit).get("requestedVersion")));
        KnowledgeDTO.Upload correction = metadata(); correction.setTitle("视频新题名"); correction.setFormat("video"); correction.setSourceVersion(2);
        correction.setExpectedRevision(number(knowledge.detail(id).get("revision"))); correction.setReviewedVideo(true);
        correction.setContent(original.replace("原始识别", "复核字幕")); sources.reuse(id, correction);
        Map<String,Object> corrected = knowledge.adminVersion(id, 3);
        assertEquals("ASR_REVIEWED", ((Map<?,?>) corrected.get("source")).get("extractionMode"));
        assertEquals(original, knowledge.adminVersion(id, 1).get("content")); assertNull(knowledge.detail(id).get("publishedVersion"));
        KnowledgeDTO.Upload renamed = metadata(); renamed.setFormat("video"); renamed.setSourceVersion(3); renamed.setTitle("校订后的题名");
        renamed.setExpectedRevision(number(knowledge.detail(id).get("revision"))); sources.reuse(id, renamed);
        assertEquals(corrected.get("content"), knowledge.adminVersion(id, 4).get("content"));
        assertEquals("ASR_REVIEWED", ((Map<?,?>) knowledge.adminVersion(id, 4).get("source")).get("extractionMode"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT asset_id) FROM knowledge_video_source", Integer.class));
    }

    @Test void videoCitationBoundsComeFromDecodedAuthoritativeSpeechRatherThanCallerOrJsonSubstring() {
        String id = savedVideo(); publish(id, 1); Map<String,Object> version = knowledge.adminVersion(id, 1);
        VideoTranscript.Speech first = VideoTranscript.parse((String) version.get("content")).segments.get(0);
        KnowledgeDTO.Hit hit = new KnowledgeDTO.Hit(); hit.setDocumentId(id); hit.setVersion(1); hit.setTitle((String) version.get("title"));
        hit.setContentHash((String) version.get("contentHash")); hit.setChunkId(MODEL_HASH); hit.setChunkHash(RagServiceClient.hash(first.text));
        hit.setChunkIndex(0); hit.setExcerpt(first.text); hit.setHeadingPath(VideoTranscript.clock(first.startMs) + "–" + VideoTranscript.clock(first.endMs));
        hit.setScore(.8); hit.setStartMs(12345L); hit.setEndMs(12346L);
        when(processor.retrieve(anyString(), anyList(), anyInt())).thenReturn(Collections.singletonList(hit));
        KnowledgeDTO.Search search = new KnowledgeDTO.Search(); search.setQuery("如何求区间和");
        Map<?,?> item = (Map<?,?>) ((List<?>) knowledge.search(search).get("items")).get(0);
        assertEquals(first.startMs, item.get("startMs")); assertEquals(first.endMs, item.get("endMs"));
        assertTrue(((String) item.get("url")).endsWith("&t=1")); assertNull(item.get("page"));
        hit.setHeadingPath("00:00:00.000–00:00:05.000"); fail(502, () -> knowledge.search(search));
    }

    @Test void oldUnfinishedDraftRemainsReachableWhenMoreThanFortyLaterImportsAreSaved() {
        String old = register(); Map<String,Object> lease = imports.claim(); imports.failed(lease, "VIDEO_ASR_FAILED", false);
        for (int i=0;i<41;i++) { clock.incrementAndGet(); String id=register(); ready(id); imports.save(id,saveMetadata(id)); }
        List<?> items=(List<?>) imports.list().get("items");assertEquals(40,items.size());assertEquals(old,((Map<?,?>)items.get(0)).get("id"));
        assertTrue(items.stream().noneMatch(value->((Map<?,?>)value).containsKey("content")));
        imports.cancel(old,change(old));assertEquals(0,quota(actor));
    }

    @Test void lostCommitReceiptPreservesTheAlreadyRegisteredOriginalAndRetryIdentity(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        KnowledgeVideoAssets files=new KnowledgeVideoAssets();ReflectionTestUtils.setField(files,"directory",directory.toString());
        KnowledgeVideoImports uncertain=spy(imports);ReflectionTestUtils.setField(uncertain,"assets",files);
        doAnswer(call->{call.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("private lost commit receipt");})
                .when(uncertain).register(anyString(),isNull(),any(),anyString(),anyString(),any());
        byte[] bytes="\0\0\0 ftypisom-local-test".getBytes(StandardCharsets.US_ASCII);KnowledgeDTO.Upload metadata=metadata();
        fail(503,()->uncertain.upload(null,metadata,"lecture.mp4","auto",new java.io.ByteArrayInputStream(bytes),bytes.length));
        Map<String,Object> stored=jdbc.queryForMap("SELECT * FROM knowledge_video_import");String asset=(String)stored.get("asset_id");
        assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(directory.resolve(asset+".bin")));assertEquals(1,quota(actor));
        ReflectionTestUtils.setField(imports,"assets",files);assertEquals(stored.get("id"),imports.upload(null,metadata,"lecture.mp4","auto",new java.io.ByteArrayInputStream(bytes),bytes.length).get("id"));
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.list(directory)){assertEquals(1,paths.count());}
    }

    @Test void aKnownRejectedRegistrationCleansItsUnadoptedFileImmediately(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        KnowledgeVideoAssets files=new KnowledgeVideoAssets();ReflectionTestUtils.setField(files,"directory",directory.toString());
        KnowledgeVideoImports rejected=spy(imports);ReflectionTestUtils.setField(rejected,"assets",files);
        doThrow(new ObjectiveException(429,"draft capacity")).when(rejected).register(anyString(),isNull(),any(),anyString(),anyString(),any());
        byte[] bytes="\0\0\0 ftypisom-local-test".getBytes(StandardCharsets.US_ASCII);
        fail(429,()->rejected.upload(null,metadata(),"lecture.mp4","auto",new java.io.ByteArrayInputStream(bytes),bytes.length));
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.list(directory)){assertEquals(0,paths.count());}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_video_import",Integer.class));
    }
}
