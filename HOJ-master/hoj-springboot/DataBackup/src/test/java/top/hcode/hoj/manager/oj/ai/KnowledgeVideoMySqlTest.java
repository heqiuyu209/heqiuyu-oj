package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;

import java.sql.Connection;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in; all writes stay in a random temporary schema, with controlled ASR/file IO. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST", matches="1")
class KnowledgeVideoMySqlTest {
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));

    private KnowledgeDTO.Upload metadata(String nonce) {
        KnowledgeDTO.Upload dto = new KnowledgeDTO.Upload(); dto.setRequestId(nonce); dto.setTitle("并发视频讲义"); dto.setVisibility("ADMIN"); return dto;
    }
    private String register(KnowledgeVideoImports imports, String nonce) {
        KnowledgeVideoDTO.Asset asset = new KnowledgeVideoDTO.Asset(); asset.setAssetId(UUID.randomUUID().toString().replace("-", "")); asset.setSourceHash(HASH); asset.setByteLength(100L);
        return (String) imports.register("alice", null, metadata(nonce), "local.mp4", "auto", asset).get("id");
    }
    private Map<String,Object> row(JdbcTemplate jdbc, String id) { return jdbc.queryForMap("SELECT * FROM knowledge_video_import WHERE id=?", id); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private KnowledgeVideoDTO.Change change(JdbcTemplate jdbc, String id) { KnowledgeVideoDTO.Change dto = new KnowledgeVideoDTO.Change(); dto.setExpectedRevision(number(row(jdbc,id).get("revision"))); return dto; }
    private KnowledgeVideoDTO.Probe probe(Map<String,Object> row) {
        KnowledgeVideoDTO.Probe dto = new KnowledgeVideoDTO.Probe(); dto.setAssetId((String)row.get("asset_id")); dto.setSourceHash(HASH); dto.setByteLength(100L);
        dto.setDurationMs(30000L); dto.setMime("video/mp4"); dto.setWidth(640); dto.setHeight(360); dto.setVideoCodec("h264"); dto.setAudioCodec("aac"); dto.setAudioIndex(1); return dto;
    }
    private <T> List<T> parallel(ExecutorService workers, IntFunction<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1); List<Future<T>> tasks = new ArrayList<>();
        for (int i=0;i<2;i++) { final int slot=i; tasks.add(workers.submit(() -> { ready.countDown(); assertTrue(start.await(5,TimeUnit.SECONDS)); return action.apply(slot); })); }
        assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown(); return Arrays.asList(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS));
    }
    private void cancelAll(JdbcTemplate jdbc, KnowledgeVideoImports imports) {
        for (String id : jdbc.queryForList("SELECT id FROM knowledge_video_import WHERE state NOT IN ('CANCELLED','SAVED')",String.class)) imports.cancel(id,change(jdbc,id));
        assertEquals(0,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all'",Integer.class));
    }

    @Test void repeatableReadProtectsQuotaLeaseCurrentRoleAndAtomicSavedVersion() throws Exception {
        String schema="hoj_video_it_"+UUID.randomUUID().toString().replace("-", "");
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password));
        long original=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class); admin.execute("CREATE DATABASE "+schema);
        ExecutorService workers=Executors.newFixedThreadPool(2); KnowledgeVideoImports imports=null;
        try {
            DriverManagerDataSource data=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
            JdbcTemplate jdbc=new JdbcTemplate(data); assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
            jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)"); jdbc.execute("CREATE TABLE role(id INT PRIMARY KEY,role VARCHAR(32))"); jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id INT,PRIMARY KEY(uid,role_id))");
            jdbc.update("INSERT INTO user_info VALUES('alice',0)"); jdbc.update("INSERT INTO role VALUES(1,'root')"); jdbc.update("INSERT INTO user_role VALUES('alice',1)");
            try(Connection connection=data.getConnection()) { for(int i=0;i<2;i++) { ScriptUtils.executeSqlScript(connection,new ClassPathResource("knowledge-base.sql")); ScriptUtils.executeSqlScript(connection,new ClassPathResource("knowledge-video.sql")); } }
            AtomicLong clock=new AtomicLong(System.currentTimeMillis()); DataSourceTransactionManager transactions=new DataSourceTransactionManager(data);
            KnowledgeManager knowledge=new KnowledgeManager(){@Override protected String uid(){return "alice";}@Override protected boolean maintainer(){return true;}@Override protected long now(){return clock.get();}};
            KnowledgeVideoImports queue=new KnowledgeVideoImports(){@Override protected long now(){return clock.get();}}; imports=queue;
            RagServiceClient processor=mock(RagServiceClient.class); KnowledgeVideoAssets assets=mock(KnowledgeVideoAssets.class);
            when(assets.configured()).thenReturn(true); when(processor.configured()).thenReturn(true);
            for(Object bean:Arrays.asList(knowledge,queue)){ReflectionTestUtils.setField(bean,"jdbc",jdbc);ReflectionTestUtils.setField(bean,"transactions",transactions);}
            ReflectionTestUtils.setField(knowledge,"index",processor);ReflectionTestUtils.setField(knowledge,"access",mock(AiAccessGuard.class));
            ReflectionTestUtils.setField(queue,"knowledge",knowledge);ReflectionTestUtils.setField(queue,"assets",assets);ReflectionTestUtils.setField(queue,"processor",processor);ReflectionTestUtils.setField(queue,"json",new ObjectMapper());

            List<String> duplicate=parallel(workers,slot->register(queue,"mysql_video_same_nonce")); assertEquals(duplicate.get(0),duplicate.get(1));
            assertEquals(1,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='alice'",Integer.class));
            register(queue,"mysql_video_second"); register(queue,"mysql_video_third");
            List<Integer> cap=parallel(workers,slot->{try{register(queue,"mysql_video_cap_"+slot);return 200;}catch(ObjectiveException e){return e.getStatus();}}); Collections.sort(cap); assertEquals(Arrays.asList(200,429),cap);
            assertEquals(4,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all'",Integer.class));

            // An outer caller already owns an old consistent snapshot before waiting for the quota mutex.
            cancelAll(jdbc,queue); register(queue,"mysql_snapshot_first"); register(queue,"mysql_snapshot_second"); register(queue,"mysql_snapshot_third");
            CountDownLatch snapshot=new CountDownLatch(1),added=new CountDownLatch(1);
            Future<Integer> staleQuota=workers.submit(()->{try{return new TransactionTemplate(transactions).execute(status->{
                assertEquals(3,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='alice'",Integer.class)); snapshot.countDown();
                try{assertTrue(added.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}
                register(queue,"mysql_snapshot_overflow");return 200;
            });}catch(ObjectiveException error){return error.getStatus();}});
            assertTrue(snapshot.await(5,TimeUnit.SECONDS)); register(queue,"mysql_snapshot_fourth");added.countDown();assertEquals(429,staleQuota.get(15,TimeUnit.SECONDS));
            cancelAll(jdbc,queue);

            String id=register(queue,"mysql_lease_video");List<Map<String,Object>> claims=parallel(workers,slot->queue.claim());
            assertEquals(1,claims.stream().filter(Objects::nonNull).count());Map<String,Object> expired=claims.stream().filter(Objects::nonNull).findFirst().get();
            clock.addAndGet(180001L);Map<String,Object> lease=queue.claim();queue.probed(expired,probe(expired));queue.failed(expired,"VIDEO_ASR_FAILED",false);
            assertEquals("RUNNING",row(jdbc,id).get("state"));queue.probed(lease,probe(lease));
            Map<String,Object> part=queue.claim();KnowledgeVideoDTO.Transcribed done=new KnowledgeVideoDTO.Transcribed();done.setAssetId((String)part.get("asset_id"));done.setSourceHash(HASH);done.setByteLength(100L);done.setDurationMs(30000L);done.setStartMs(0L);done.setEndMs(30000L);done.setLanguage("zh");done.setEngine("faster-whisper-cpu-int8-v1");done.setModelHash(HASH);
            KnowledgeVideoDTO.Speech speech=new KnowledgeVideoDTO.Speech();speech.setStartMs(1000L);speech.setEndMs(5000L);speech.setText("前缀和用于静态区间求和。");done.setSegments(Collections.singletonList(speech));queue.transcribed(part,done);
            long readyRevision=number(row(jdbc,id).get("revision"));List<Map<String,Object>> saved=parallel(workers,slot->{KnowledgeVideoDTO.Save dto=new KnowledgeVideoDTO.Save();dto.setRequestId("mysql_video_save");dto.setTitle("并发视频讲义");dto.setVisibility("ADMIN");dto.setImportRevision(readyRevision);return queue.save(id,dto);});
            assertEquals(saved.get(0).get("id"),saved.get(1).get("id"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_video_source",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_job",Integer.class));assertNull(saved.get(0).get("publishedVersion"));
            assertEquals(0,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all'",Integer.class));

            String revoked=register(queue,"mysql_revoked_video");Map<String,Object> beforeRevoke=queue.claim();CountDownLatch seen=new CountDownLatch(1),changed=new CountDownLatch(1);
            Future<?> completion=workers.submit(()->new TransactionTemplate(transactions).execute(status->{jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE uid='alice'",Integer.class);seen.countDown();try{assertTrue(changed.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}queue.probed(beforeRevoke,probe(beforeRevoke));return null;}));
            assertTrue(seen.await(5,TimeUnit.SECONDS));jdbc.update("DELETE FROM user_role WHERE uid='alice'");changed.countDown();completion.get(15,TimeUnit.SECONDS);
            assertEquals("FAILED",row(jdbc,revoked).get("state"));assertEquals("ACCESS_REVOKED",row(jdbc,revoked).get("last_error"));jdbc.update("INSERT INTO user_role VALUES('alice',1)");
            KnowledgeVideoDTO.Change cancel=change(jdbc,revoked);parallel(workers,slot->queue.cancel(revoked,cancel));assertEquals(0,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all'",Integer.class));

            Path media=Files.createTempDirectory("hoj-video-cleanup-it-");KnowledgeVideoAssets localFiles=new KnowledgeVideoAssets();ReflectionTestUtils.setField(localFiles,"directory",media.toString());
            KnowledgeVideoCleanup cleanup=new KnowledgeVideoCleanup(){@Override protected long now(){return clock.get();}};
            ReflectionTestUtils.setField(cleanup,"assets",localFiles);ReflectionTestUtils.setField(cleanup,"jdbc",jdbc);ReflectionTestUtils.setField(cleanup,"transactions",transactions);
            String pendingAsset=UUID.randomUUID().toString().replace("-", "");Path pending=media.resolve(pendingAsset+".bin");
            String savedAsset=(String)part.get("asset_id");Path savedFile=media.resolve(savedAsset+".bin");
            try{
                Files.write(pending,new byte[100]);Files.setLastModifiedTime(pending,FileTime.fromMillis(clock.get()-KnowledgeVideoCleanup.GRACE_MS));
                CountDownLatch readSnapshot=new CountDownLatch(1),committed=new CountDownLatch(1);
                Future<Integer> collector=workers.submit(()->new TransactionTemplate(transactions).execute(status->{
                    jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_video_import",Integer.class);readSnapshot.countDown();
                    try{assertTrue(committed.await(5,TimeUnit.SECONDS));return cleanup.processBatch();}catch(Exception error){throw new RuntimeException(error);}
                }));
                assertTrue(readSnapshot.await(5,TimeUnit.SECONDS));
                KnowledgeVideoDTO.Asset reference=new KnowledgeVideoDTO.Asset();reference.setAssetId(pendingAsset);reference.setSourceHash(HASH);reference.setByteLength(100L);
                String kept=(String)queue.register("alice",null,metadata("mysql_gc_reference"),"local.mp4","auto",reference).get("id");committed.countDown();
                assertEquals(0,collector.get(15,TimeUnit.SECONDS));assertTrue(Files.exists(pending));
                // A failed unlink after cancellation must be retried without releasing quota again.
                queue.cancel(kept,change(jdbc,kept));assertTrue(Files.exists(pending));assertEquals(1,cleanup.processBatch());assertFalse(Files.exists(pending));
                assertEquals(0,jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all'",Integer.class));
                // Even after the import record is removed, an audit version owns its original.
                jdbc.update("DELETE FROM knowledge_video_import WHERE id=?",id);Files.write(savedFile,new byte[100]);Files.setLastModifiedTime(savedFile,FileTime.fromMillis(clock.get()-KnowledgeVideoCleanup.GRACE_MS));
                assertEquals(0,cleanup.processBatch());assertTrue(Files.exists(savedFile));
            }finally{
                cleanup.close();assertTrue(media.toAbsolutePath().getFileName().toString().startsWith("hoj-video-cleanup-it-"));
                Files.deleteIfExists(pending);Files.deleteIfExists(savedFile);Files.delete(media);
            }
        } finally {
            if(imports!=null)imports.close();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));
            assertTrue(schema.matches("hoj_video_it_[a-f0-9]{32}"));admin.execute("DROP DATABASE "+schema);assertEquals(original,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));
        }
    }
}
