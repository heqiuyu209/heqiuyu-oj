package top.hcode.hoj.remote;

import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static top.hcode.hoj.remote.PersonalRemoteTaskStore.*;

class NowCoderTaskJournalTest {
    JdbcTemplate jdbc;TransactionTemplate tx;PersonalRemoteTaskStore tasks;NowCoderTaskJournal journal;AtomicLong now;
    NowCoderJudgeProtocol.Context context=new NowCoderJudgeProtocol.Context("12345","1338275","218144","","2","C++");
    FakeSource source;NowCoderJudgeProtocol protocol;
    static class FakeSource extends PersonalRemoteTransport {
        String acknowledgement="{\"code\":0,\"data\":{\"id\":\"opaque-job\"}}",status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"submissionId\":100,\"status\":5}}";int posts;
        FakeSource(){super("NOWCODER",RemoteSession.parse("NOWCODER","t=unused-fixture"),()->{});}
        @Override public String get(String path){return "<script>\nwindow.isLogin=true;\nwindow.globalInfo={ownerId:'12345'};\n</script>";}
        @Override public String nowcoderAccessToken(){return "{\"success\":true,\"data\":{\"accessToken\":\"unused-token\"}}";}
        @Override public String nowcoderSubmit(String body,Runnable barrier){barrier.run();posts++;return acknowledgement;}
        @Override public String nowcoderStatus(Map<String,String> query){return status;}
    }
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:nc_journal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000","sa","");jdbc=new JdbcTemplate(ds);jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(120),is_remote BOOLEAN)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT PRIMARY KEY,uid VARCHAR(32),pid BIGINT,cid BIGINT,gid BIGINT,language VARCHAR(255),code TEXT,status INT,version INT DEFAULT 0)");jdbc.execute("CREATE TABLE grading_event(submit_id BIGINT PRIMARY KEY)");
        for(String file:Arrays.asList("external-learning.sql","remote-credentials.sql","personal-remote-tasks.sql","nowcoder-remote.sql")){
            String ddl=StreamUtils.copyToString(new ClassPathResource(file).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","");try(Connection connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        jdbc.update("INSERT INTO user_info VALUES('alice',0)");jdbc.update("INSERT INTO problem VALUES(1,'NC-218144',TRUE)");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice','NOWCODER','12345','12345','12345',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");jdbc.update("INSERT INTO remote_user_credential VALUES(1,'s1:synthetic-not-a-real-session',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO judge VALUES(1,'alice',1,0,NULL,'C++','synthetic source',5,0)");DataSourceTransactionManager tm=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(tm);now=new AtomicLong(1800000000000L);tasks=new PersonalRemoteTaskStore(jdbc,tm,now::get);journal=new NowCoderTaskJournal(jdbc,tm,tasks);source=new FakeSource();protocol=new NowCoderJudgeProtocol(source);
    }
    @AfterEach void close(){source.close();}
    void enqueue(){tx.execute(status->tasks.enqueue("alice",1,1,"request_nc_00000001","2",null,1L,context));}
    Map<String,Object> claim(){return tasks.claim("NOWCODER");}
    String token(Map<String,Object> task){return (String)task.get("lease_token");}
    String state(){return jdbc.queryForObject("SELECT state FROM personal_remote_task",String.class);}
    void acknowledge(){enqueue();Map<String,Object> task=claim();NowCoderJudgeProtocol.Acknowledgement reply=protocol.submit(context,"synthetic source",()->assertTrue(tasks.beginPost(1,token(task),90)));journal.acknowledged(1,token(task),reply);}
    void grade(){jdbc.update("INSERT INTO grading_event VALUES(1)");jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1");}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void snapshotEnqueueIsAtomicTokenFreeAndBoundToTheOriginalProblemAccountAndLanguage(){
        assertThrows(IllegalStateException.class,()->tasks.enqueue("alice",1,1,"request_nc_00000001","2",null,1L,context));
        assertThrows(RuntimeException.class,()->tx.execute(status->{tasks.enqueue("alice",1,1,"request_nc_00000001","2",null,1L,context);throw new RuntimeException("rollback");}));assertEquals(0,count("personal_remote_task"));assertEquals(0,count("personal_remote_nowcoder"));assertEquals(0,count("personal_remote_lane"));
        assertThrows(Conflict.class,()->tx.execute(status->tasks.enqueue("alice",1,1,"request_nc_00000001","2")));
        enqueue();assertEquals(context.signature(),journal.context(1).signature());String snapshot=jdbc.queryForObject("SELECT context_json FROM personal_remote_nowcoder",String.class);assertFalse(snapshot.contains("token"));assertFalse(snapshot.contains("synthetic source"));assertEquals(1,count("personal_remote_nowcoder"));
    }
    @Test void mismatchedSourceSnapshotsCannotEnterTheQueue(){
        for(NowCoderJudgeProtocol.Context invalid:Arrays.asList(new NowCoderJudgeProtocol.Context("99999","1338275","218144","","2","C++"),new NowCoderJudgeProtocol.Context("12345","1338275","1","","2","C++"),new NowCoderJudgeProtocol.Context("12345","1338275","218144","1","2","C++"),new NowCoderJudgeProtocol.Context("12345","1338275","218144","","11","Python 3")))assertThrows(Conflict.class,()->tx.execute(status->tasks.enqueue("alice",1,1,"request_nc_00000001","2",null,1L,invalid)));
        assertEquals(0,count("personal_remote_task"));assertEquals(0,count("personal_remote_nowcoder"));
    }
    @Test void opaqueJobIdSurvivesRestartAndOnlyConfirmedPublicSubmissionIdCanGradeOnce(){
        acknowledge();assertEquals("opaque-job",journal.job(1));assertEquals("POLLING",state());assertNull(jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task",Long.class));
        Map<String,Object> poll=claim();NowCoderJudgeProtocol.Status result=protocol.status(context,journal.job(1));assertTrue(journal.result(1,token(poll),result,this::grade));assertEquals("COMPLETED",state());assertEquals(100L,jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task",Long.class));assertEquals(100L,jdbc.queryForObject("SELECT source_submission_id FROM personal_remote_nowcoder",Long.class));assertEquals(1,count("grading_event"));
        assertThrows(Conflict.class,()->journal.result(1,token(poll),result,this::grade));assertEquals(1,count("grading_event"));assertEquals(1,source.posts);
    }
    @Test void gradingAndSourceIdPublicationRollBackTogether(){
        acknowledge();Map<String,Object> poll=claim();NowCoderJudgeProtocol.Status result=protocol.status(context,"opaque-job");
        assertThrows(RuntimeException.class,()->journal.result(1,token(poll),result,()->{grade();throw new RuntimeException("fixture failure");}));assertEquals(0,count("grading_event"));assertEquals(5,jdbc.queryForObject("SELECT status FROM judge",Integer.class));assertNull(jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task",Long.class));assertEquals("POLLING",state());
        assertTrue(journal.result(1,token(poll),result,this::grade));assertEquals(1,count("grading_event"));
    }
    @Test void staleAcknowledgementCannotOverwriteTheRecoveredLeaseOrReplayTheCode(){
        enqueue();Map<String,Object> first=claim();NowCoderJudgeProtocol.Acknowledgement reply=protocol.submit(context,"synthetic source",()->assertTrue(tasks.beginPost(1,token(first),90)));
        now.addAndGet(90001);Map<String,Object> recovered=claim();assertEquals("RECONCILING",recovered.get("state"));assertThrows(Conflict.class,()->journal.acknowledged(1,token(first),reply));assertThrows(Conflict.class,()->tasks.beginPost(1,token(recovered),90));assertNull(journal.job(1));
        journal.acknowledged(1,token(recovered),reply);assertEquals("POLLING",state());assertEquals(1,source.posts);assertEquals(1,jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));
    }
    @Test void wrongCodeAcknowledgementOrForeignResultCannotSettleTheTask(){
        enqueue();Map<String,Object> posted=claim();assertTrue(tasks.beginPost(1,token(posted),90));NowCoderJudgeProtocol.Acknowledgement wrong=protocol.submit(context,"different source",()->{});assertThrows(Conflict.class,()->journal.acknowledged(1,token(posted),wrong));assertNull(journal.job(1));
        NowCoderJudgeProtocol.Acknowledgement correct=protocol.submit(context,"synthetic source",()->{});journal.acknowledged(1,token(posted),correct);Map<String,Object> poll=claim();
        NowCoderJudgeProtocol.Context other=new NowCoderJudgeProtocol.Context("12345","99999","218144","","2","C++");NowCoderJudgeProtocol.Status foreign=protocol.status(other,"opaque-job");assertThrows(Conflict.class,()->journal.result(1,token(poll),foreign,this::grade));assertEquals(0,count("grading_event"));
    }
    @Test void waitingResultPreservesAttemptCountAndRevokedOrChangedContextCannotBeUsed(){
        acknowledge();source.status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"status\":107}}";Map<String,Object> poll=claim();assertFalse(journal.result(1,token(poll),protocol.status(context,"opaque-job"),this::grade));assertEquals("POLLING",state());assertEquals(0,count("grading_event"));
        now.addAndGet(5001);poll=claim();jdbc.update("UPDATE personal_remote_nowcoder SET context_json=REPLACE(context_json,'1338275','99999')");assertFalse(tasks.authorize(1,token(poll)));assertEquals("NEEDS_REVIEW",state());assertEquals(1,jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));
    }
    @Test void lostResponseRemainsReadOnlyAndTaskDeletionCascadesOnlyItsOwnSourceSnapshot(){
        enqueue();Map<String,Object> task=claim();assertTrue(tasks.beginPost(1,token(task),90));tasks.uncertain(1,token(task));now.addAndGet(5001);Map<String,Object> recovered=claim();assertNull(journal.job(1));assertEquals("RECONCILING",recovered.get("state"));assertThrows(Conflict.class,()->tasks.beginPost(1,token(recovered),90));
        jdbc.update("DELETE FROM judge WHERE submit_id=1");assertEquals(0,count("personal_remote_task"));assertEquals(0,count("personal_remote_nowcoder"));assertEquals(1,count("external_account"));
    }
}
