package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderContextManagerTest {
    JdbcTemplate jdbc;NowCoderContextManager contexts;ObjectMapper json=new ObjectMapper();long clock=1800000000000L;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:nc_context_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        initialize(source);
    }
    void initialize(DriverManagerDataSource source) throws Exception {
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0)");
        for(String file:Arrays.asList("external-learning.sql","nowcoder-context.sql")){
            String ddl=StreamUtils.copyToString(new ClassPathResource(file).getInputStream(),StandardCharsets.UTF_8);
            if(source.getUrl().startsWith("jdbc:h2:"))ddl=ddl.replace(" COLLATE=utf8mb4_unicode_ci","");
            try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        Timestamp now=new Timestamp(clock);
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at) VALUES(1,'alice','NOWCODER','12345','12345','12345',1,?,?,?)",now,now,now);
        jdbc.update("INSERT INTO external_sync_job(account_id,stage,scan_token) VALUES(1,'DONE','coding-scan')");
        contexts=new NowCoderContextManager(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(contexts,"jdbc",jdbc);ReflectionTestUtils.setField(contexts,"transactions",new DataSourceTransactionManager(source));
    }
    ObjectNode listing(boolean restricted,boolean team){
        ObjectNode value=json.createObjectNode().put("uid","12345").put("page",1).put("total",1).put("pages",1);
        value.putArray("items").addObject().put("contestId",99785).put("title","Public contest").put("startMs",1000000).put("endMs",2000000).put("team",team).put("restricted",restricted).put("participation","OFFICIAL").put("rank",10).put("officialAccepted",1);return value;
    }
    ObjectNode problems(){
        ObjectNode value=json.createObjectNode().put("contestId",99785).put("page",1).put("total",1).put("pages",1);
        value.putArray("items").addObject().put("problemKey","nowcoder:acm:218144").put("index","A").put("title","Global problem");return value;
    }
    ObjectNode status(){
        ObjectNode value=json.createObjectNode().put("uid","12345").put("contestId",99785).put("page",1).put("total",1).put("pages",1);
        value.putArray("items").addObject().put("id",7).put("problemKey","nowcoder:acm:218144").put("index","A").put("verdict","OK").put("submittedMs",1500000);return value;
    }
    Map<String,Object> step(JsonNode data){Map<String,Object> job=contexts.claim();assertNotNull(job);contexts.apply(job,data);return job;}
    void startDetails(){ObjectNode list=listing(false,false);step(list);step(list);step(problems());step(problems());}
    void complete(){startDetails();step(status());step(status());}
    String stage(){return jdbc.queryForObject("SELECT stage FROM external_nowcoder_context_job WHERE account_id=1",String.class);}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @SuppressWarnings("unchecked") Map<String,Object> contest(){return ((List<Map<String,Object>>)contexts.view(1,true).get("contests")).get(0);}
    void regular(long id,long second,String key){jdbc.update("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(1,?,?,'OK',?,0,'coding-scan')",id,key,second);}
    @Test void completedSnapshotDoesNotDuplicateCanonicalSubmissionsAndKeepsPostContestEvidenceSeparate(){
        regular(7,1500,"nowcoder:acm:218144");regular(8,2500,"nowcoder:acm:218144");regular(9,2600,"nowcoder:acm:999");
        startDetails();assertEquals(false,contexts.view(1,true).get("available"));step(status());assertEquals(false,contexts.view(1,true).get("available"));step(status());
        assertEquals("DONE",stage());assertEquals(true,contexts.view(1,true).get("submissionCoverageComplete"));assertEquals(3,count("external_submission"));assertEquals(1,count("external_nowcoder_contest_submission"));
        Map<String,Object> row=contest();assertEquals(1L,((Number)row.get("contestSolved")).longValue());assertEquals(1L,((Number)row.get("postContestSolved")).longValue());assertEquals(1L,((Number)row.get("contestSubmissions")).longValue());
        assertFalse(contexts.view(1,false).containsKey("lastError"));assertFalse(contexts.view(1,true).containsKey("scan_token"));
    }
    @Test void noInitialHistoryNoJobAndExplicitEmptyParticipationCanFinish(){
        jdbc.update("UPDATE external_account SET history_complete=0");assertNull(contexts.claim());assertEquals(0,count("external_nowcoder_context_job"));
        jdbc.update("UPDATE external_account SET history_complete=1");ObjectNode empty=json.createObjectNode().put("uid","12345").put("page",1).put("total",0).put("pages",0);empty.putArray("items");
        step(empty);step(empty);assertEquals("DONE",stage());assertEquals(true,contexts.view(1,true).get("listComplete"));assertEquals(0,contexts.view(1,true).get("totalContests"));
    }
    @Test void privateAndTeamParticipationPublishAggregateWithoutRequestingIndividualDetails(){
        for(boolean team:new boolean[]{false,true}){
            if(count("external_nowcoder_context_job")>0)contexts.request(1);
            ObjectNode list=listing(!team,team);step(list);step(list);assertEquals("DONE",stage());assertEquals(false,contexts.view(1,true).get("submissionCoverageComplete"));
            assertNull(contest().get("contestSolved"));assertNull(contest().get("postContestSolved"));assertEquals(team?"TEAM":"RESTRICTED",contest().get("detailState"));
            assertEquals(0,count("external_nowcoder_contest_submission"));assertEquals(0,count("external_nowcoder_contest_problem"));
        }
    }
    @Test void changedHeadRetainsLastCompleteSnapshotAndRestartsStaging(){
        complete();String published=jdbc.queryForObject("SELECT published_token FROM external_nowcoder_context_job",String.class);contexts.request(1);
        step(listing(false,false));ObjectNode changed=listing(false,false);((ObjectNode)changed.path("items").get(0)).put("title","Changed at source");step(changed);
        assertEquals("LIST",stage());assertEquals(published,jdbc.queryForObject("SELECT published_token FROM external_nowcoder_context_job",String.class));assertEquals("Public contest",contest().get("title"));
        assertEquals(1,count("external_nowcoder_contest"));assertEquals(1,count("external_nowcoder_contest_submission"));
    }
    @Test void newlyKnownRestrictionRevokesOldDetailBeforeTheWholeReplacementScan(){
        complete();contexts.request(1);step(listing(true,false));assertEquals("VERIFY_LIST",stage());
        assertEquals("RESTRICTED",contest().get("detailState"));assertNull(contest().get("contestSolved"));assertEquals(0,contexts.view(1,false).get("coveredContests"));
    }
    @Test void hiddenSourceRankDisappearsFromOldPublishedMetadataImmediately(){
        complete();contexts.request(1);ObjectNode hidden=listing(false,false);((ObjectNode)hidden.path("items").get(0)).putNull("rank").putNull("officialAccepted");step(hidden);
        assertNull(contest().get("rank"));assertNull(contest().get("officialAccepted"));
    }
    @Test void expiredLeaseCannotWriteOrFailANewerClaimAndBusyDeferralDoesNotConsumeFailureBudget(){
        Map<String,Object> old=contexts.claim();clock+=90001;Map<String,Object> current=contexts.claim();assertNotEquals(old.get("lease_token"),current.get("lease_token"));
        contexts.apply(old,listing(false,false));contexts.failed(old,"private source response");assertEquals(0,count("external_nowcoder_contest"));
        assertEquals(current.get("lease_token"),jdbc.queryForObject("SELECT lease_token FROM external_nowcoder_context_job",String.class));contexts.defer(current);
        assertNull(jdbc.queryForObject("SELECT lease_token FROM external_nowcoder_context_job",String.class));assertEquals(0,jdbc.queryForObject("SELECT failures FROM external_nowcoder_context_job",Integer.class));
    }
    @Test void unverifiedProblemOrLeakedPracticeRecordsCannotPublishContestEvidence(){
        startDetails();ObjectNode wrong=status();((ObjectNode)wrong.path("items").get(0)).put("problemKey","nowcoder:acm:999");Map<String,Object> job=contexts.claim();
        assertThrows(IllegalArgumentException.class,()->contexts.apply(job,wrong));assertEquals("STATUS",stage());assertEquals(0,count("external_nowcoder_contest_submission"));
        assertNull(jdbc.queryForObject("SELECT page_total FROM external_nowcoder_context_job WHERE account_id=1",Integer.class));
        contexts.apply(job,status());assertEquals("VERIFY_STATUS",stage());
    }
    @Test void officialContestCannotSilentlyIncludePostContestPractice(){
        startDetails();ObjectNode late=status();((ObjectNode)late.path("items").get(0)).put("submittedMs",2500000);step(late);
        assertEquals("LIST",stage());assertEquals(0,count("external_nowcoder_contest_submission"));assertEquals(false,contexts.view(1,true).get("available"));
    }
    @Test void failedNewPageAndDisabledAccountsDoNotOverwritePublishedContext(){
        complete();contexts.request(1);Map<String,Object> job=contexts.claim();contexts.failed(job,"source response contains private text");
        assertEquals(true,contexts.view(1,true).get("available"));assertEquals("BACKOFF",contexts.view(1,true).get("syncState"));assertFalse(contexts.view(1,true).get("lastError").toString().contains("private"));
        jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");clock+=3600001;assertNull(contexts.claim());
    }
    @Test void canonicalScanInProgressHidesDerivedLaterCountsAndUnlinkCascadesOnlyOwnedContext(){
        regular(8,2500,"nowcoder:acm:218144");complete();assertNotNull(contest().get("postContestSolved"));jdbc.update("UPDATE external_sync_job SET stage='STATUS'");assertNull(contest().get("postContestSolved"));
        jdbc.update("DELETE FROM external_account WHERE id=1");for(String table:Arrays.asList("external_nowcoder_context_job","external_nowcoder_contest","external_nowcoder_contest_problem","external_nowcoder_contest_submission"))assertEquals(0,count(table));
    }
}
