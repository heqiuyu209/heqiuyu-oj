package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Fake clock after a real admin read, plus an actual processOne dispatch control. */
class VideoLeaseTest {
    static final String HASH=String.join("",Collections.nCopies(64,"a"));
    AtomicLong clock=new AtomicLong(1800000000000L);HookJdbc jdbc;KnowledgeVideoImports queue;
    RagServiceClient processor;KnowledgeVideoAssets assets;Runnable afterAdmin;
    class HookJdbc extends JdbcTemplate {
        HookJdbc(DriverManagerDataSource ds){super(ds);}
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
            List<Map<String,Object>> rows=super.queryForList(sql,args);
            if(sql.startsWith("SELECT u.uuid FROM user_info") && afterAdmin!=null)afterAdmin.run();return rows;
        }
    }
    @BeforeEach void setup() throws Exception {initialize(new DriverManagerDataSource("jdbc:h2:mem:video_lease_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));}
    void initialize(DriverManagerDataSource ds) throws Exception {
        jdbc=new HookJdbc(ds);
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.execute("CREATE TABLE role(id INT PRIMARY KEY,role VARCHAR(32))");jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id INT,PRIMARY KEY(uid,role_id))");
        jdbc.update("INSERT INTO user_info VALUES('alice',0)");jdbc.update("INSERT INTO role VALUES(1,'root')");jdbc.update("INSERT INTO user_role VALUES('alice',1)");
        for(String name:Arrays.asList("knowledge-base.sql","knowledge-video.sql")){
            String ddl=StreamUtils.copyToString(new ClassPathResource(name).getInputStream(),StandardCharsets.UTF_8);
            if(ds.getUrl().startsWith("jdbc:h2:"))ddl=ddl.replace(" COLLATE=utf8mb4_bin","");
            try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        jdbc.update("UPDATE knowledge_video_quota SET active_count=1 WHERE owner='_all'");jdbc.update("INSERT INTO knowledge_video_quota VALUES('alice',1)");
        queue=new KnowledgeVideoImports(){@Override protected long now(){return clock.get();}};
        processor=mock(RagServiceClient.class);assets=mock(KnowledgeVideoAssets.class);
        when(processor.configured()).thenReturn(true);when(assets.configured()).thenReturn(true);
        ReflectionTestUtils.setField(queue,"jdbc",jdbc);ReflectionTestUtils.setField(queue,"transactions",new DataSourceTransactionManager(ds));
        ReflectionTestUtils.setField(queue,"json",new ObjectMapper());ReflectionTestUtils.setField(queue,"processor",processor);ReflectionTestUtils.setField(queue,"assets",assets);
    }
    @AfterEach void close(){if(queue!=null)queue.close();}
    Map<String,Object> prepare(String stage){
        Map<String,Object> v=new LinkedHashMap<>();v.put("id",UUID.randomUUID().toString().replace("-",""));v.put("requested_by","alice");v.put("processing_by","alice");v.put("request_id","lease_"+v.get("id"));v.put("input_hash",HASH);v.put("title","local fixture");v.put("visibility","ADMIN");v.put("source_name","fixture.mp4");v.put("asset_id",UUID.randomUUID().toString().replace("-",""));v.put("source_hash",HASH);v.put("byte_length",100L);v.put("language","zh");v.put("state","QUEUED");v.put("stage",stage);v.put("segments","[]");v.put("next_run_at",new Timestamp(clock.get()));v.put("revision",1L);v.put("created_at",new Timestamp(clock.get()));v.put("updated_at",new Timestamp(clock.get()));
        if("TRANSCRIBE".equals(stage))v.put("duration_ms",30000L);
        jdbc.update("INSERT INTO knowledge_video_import("+String.join(",",v.keySet())+") VALUES("+String.join(",",Collections.nCopies(v.size(),"?"))+")",v.values().toArray());return queue.claim();
    }
    Map<String,Object> row(Map<String,Object> job){return jdbc.queryForMap("SELECT * FROM knowledge_video_import WHERE id=?",job.get("id"));}
    long deadline(Map<String,Object> job){return ((Timestamp)row(job).get("lease_until")).getTime();}
    KnowledgeVideoDTO.Probe probe(Map<String,Object> job){KnowledgeVideoDTO.Probe d=new KnowledgeVideoDTO.Probe();d.setAssetId((String)job.get("asset_id"));d.setSourceHash(HASH);d.setByteLength(100L);d.setDurationMs(30000L);d.setMime("video/mp4");return d;}
    KnowledgeVideoDTO.Transcribed transcript(Map<String,Object> job){KnowledgeVideoDTO.Transcribed d=new KnowledgeVideoDTO.Transcribed();d.setAssetId((String)job.get("asset_id"));d.setSourceHash(HASH);d.setByteLength(100L);d.setDurationMs(30000L);d.setStartMs(0L);d.setEndMs(30000L);d.setLanguage("zh");d.setEngine("faster-whisper-cpu-int8-v1");d.setModelHash(HASH);KnowledgeVideoDTO.Speech s=new KnowledgeVideoDTO.Speech();s.setStartMs(1000L);s.setEndMs(5000L);s.setText("算法讲义。");d.setSegments(Collections.singletonList(s));return d;}
    void invoke(String stage,Map<String,Object> job){if("PROBE".equals(stage))queue.probed(job,probe(job));else queue.transcribed(job,transcript(job));}
    Map<String,Object> snapshot(Map<String,Object> job){Map<String,Object> out=new LinkedHashMap<>();out.put("task",row(job));out.put("quota",jdbc.queryForList("SELECT * FROM knowledge_video_quota ORDER BY owner"));for(String table:Arrays.asList("knowledge_document","knowledge_version","knowledge_job","knowledge_video_source"))out.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return out;}
    void assertFinished(String stage,Map<String,Object> job,boolean allowed){Map<String,Object> r=row(job);assertEquals(allowed?("PROBE".equals(stage)?"QUEUED":"READY"):"FAILED",r.get("state"));assertNull(r.get("lease_token"));assertEquals(allowed?null:"ACCESS_REVOKED",r.get("last_error"));}
    void assertOutcome(String stage,boolean allowed,boolean live,Map<String,Object> job,Map<String,Object> before){
        if(live)assertFinished(stage,job,allowed);
        else{assertTrue(before.equals(snapshot(job)),"Expired callback changed task, quota or published metadata");Map<String,Object> fresh=queue.claim();assertNotNull(fresh);assertNotEquals(job.get("lease_token"),fresh.get("lease_token"));afterAdmin=null;invoke(stage,fresh);assertFinished(stage,fresh,allowed);}
        verifyNoInteractions(processor);
    }
    static Stream<Arguments> cases(){return Stream.of("PROBE","TRANSCRIBE").flatMap(stage->Stream.of(false,true).flatMap(allowed->Stream.of(false,true).map(live->Arguments.of(stage,allowed,live))));}
    @ParameterizedTest @MethodSource("cases")
    void leaseMustRemainLiveAfterAdminRead(String stage,boolean allowed,boolean live){
        Map<String,Object> job=prepare(stage);if(!allowed)jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");
        Map<String,Object> before=snapshot(job);long cutoff=deadline(job);afterAdmin=()->clock.set(cutoff-(live?1:0));invoke(stage,job);assertOutcome(stage,allowed,live,job,before);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void preflightExpirySkipsVideoProcessor(boolean live){
        Map<String,Object> initial=prepare("PROBE");long cutoff=deadline(initial);jdbc.update("UPDATE knowledge_video_import SET state='QUEUED',lease_token=NULL,lease_until=NULL WHERE id=?",initial.get("id"));
        when(processor.probeVideo(any())).thenReturn(probe(initial));afterAdmin=()->clock.set(cutoff-(live?1:0));assertTrue(queue.processOne());
        if(live){verify(processor).probeVideo(any());assertEquals("TRANSCRIBE",row(initial).get("stage"));}
        else{verify(processor,never()).probeVideo(any());assertEquals("RUNNING",row(initial).get("state"));assertEquals("PROBE",row(initial).get("stage"));}
    }
}
