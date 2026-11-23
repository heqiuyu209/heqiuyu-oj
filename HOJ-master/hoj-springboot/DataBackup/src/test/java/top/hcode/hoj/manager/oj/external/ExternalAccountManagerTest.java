package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAccountManagerTest {
    JdbcTemplate jdbc; ObjectMapper json=new ObjectMapper(); ExternalAccountManager manager; LearningProfileManager profiles;
    String actor; volatile long clock; JsonNode cfHead;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:external_learning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new JdbcTemplate(source).execute("DROP ALL OBJECTS");initialize(source);
    }
    void initialize(DriverManagerDataSource source) throws Exception {
        jdbc=new JdbcTemplate(source);cfHead=null;
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0),('disabled',1)");
        String ddl=StreamUtils.copyToString(new ClassPathResource("external-learning.sql").getInputStream(),StandardCharsets.UTF_8);
        // H2 does not implement MySQL's table collation clause; real MySQL migration is checked separately.
        try(Connection connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ByteArrayResource((source.getUrl().startsWith("jdbc:h2:")?ddl.replace(" COLLATE=utf8mb4_unicode_ci", ""):ddl).getBytes(StandardCharsets.UTF_8)));}
        String credentials=StreamUtils.copyToString(new ClassPathResource("remote-credentials.sql").getInputStream(),StandardCharsets.UTF_8);
        try(Connection connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ByteArrayResource((source.getUrl().startsWith("jdbc:h2:")?credentials.replace(" COLLATE=utf8mb4_unicode_ci", ""):credentials).getBytes(StandardCharsets.UTF_8)));}
        clock=1800000000000L;actor="alice";
        profiles=mock(LearningProfileManager.class);when(profiles.publicProgrammingEvidence(anyString())).thenReturn(Collections.emptyList());
        manager=new ExternalAccountManager(){@Override protected long now(){return clock;}@Override protected String uid(){return actor;}};
        ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(manager,"json",json);ReflectionTestUtils.setField(manager,"profiles",profiles);ReflectionTestUtils.setField(manager,"catalog",new AlgorithmCatalog());
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> accounts(Map<String,Object> view){return (List<Map<String,Object>>)view.get("accounts");}
    @SuppressWarnings("unchecked") Map<String,Object> summary(Map<String,Object> view){return (Map<String,Object>)view.get("summary");}
    Map<String,Object> own(){return accounts(manager.mine()).get(0);}
    long link(){return ((Number)accounts(manager.link("CODEFORCES","Tourist")).get(0).get("id")).longValue();}
    ArrayNode profile(String organization){ArrayNode a=json.createArrayNode();a.addObject().put("handle","tourist").put("registrationTimeSeconds",100).put("rating",3000).put("maxRating",3900).put("organization",organization);return a;}
    ArrayNode ratings(){ArrayNode a=json.createArrayNode();a.addObject().put("contestId",10).put("contestName","Round 10").put("rank",2).put("oldRating",2900).put("newRating",3000).put("ratingUpdateTimeSeconds",1234);return a;}
    ObjectNode submission(long id,String verdict,int contest,String index,int rating,boolean team){
        ObjectNode s=json.createObjectNode().put("id",id).put("creationTimeSeconds",1000+id).put("verdict",verdict);
        ObjectNode p=s.putObject("problem").put("contestId",contest).put("index",index).put("name","Problem");if(rating>0)p.put("rating",rating);p.putArray("tags").add("dp");
        ObjectNode author=s.putObject("author");author.putArray("members").addObject().put("handle","tourist");if(team)author.put("teamId",7);return s;
    }
    ArrayNode page(ObjectNode... submissions){ArrayNode a=json.createArrayNode();for(ObjectNode s:submissions)a.add(s);return a;}
    void apply(JsonNode result){
        Map<String,Object> job=manager.claim();assertNotNull(job);
        if("PROFILE".equals(job.get("stage")))cfHead=null;
        if("STATUS".equals(job.get("stage")) && ((Number)job.get("from_index")).intValue()==1)cfHead=result.deepCopy();
        manager.apply(job,result);
        if("VERIFY_STATUS".equals(jdbc.queryForObject("SELECT stage FROM external_sync_job WHERE account_id=?",String.class,job.get("account_id")))){
            Map<String,Object> verification=manager.claim();assertNotNull(verification);assertNotNull(cfHead);manager.apply(verification,cfHead);
        }
    }
    void ready(String organization){apply(profile(organization));apply(ratings());}
    String proof(long id){manager.proof(id);return (String)own().get("proofCode");}
    @Test void ownAssociationsArePrivateAndAllMutationsUseServerIdentity() {
        long id=link();assertEquals(1,accounts(manager.mine()).size());assertTrue(accounts(manager.publicView("alice")).isEmpty());
        assertEquals(409,assertThrows(ObjectiveException.class,()->manager.link("CODEFORCES","TOURIST")).getStatus());
        actor="bob";assertTrue(accounts(manager.mine()).isEmpty());
        assertEquals(404,assertThrows(ObjectiveException.class,()->manager.proof(id)).getStatus());
        assertThrows(ObjectiveException.class,()->manager.requestSync(id,false));assertThrows(ObjectiveException.class,()->manager.unlink(id));
        actor="disabled";assertEquals(404,assertThrows(ObjectiveException.class,manager::mine).getStatus());
    }
    @Test void validationAndAccountLimitDoNotCreateExtraJobs() {
        assertThrows(ObjectiveException.class,()->manager.link("UNSUPPORTED","tourist"));assertThrows(ObjectiveException.class,()->manager.link("CODEFORCES","a?x=1"));
        for(int n=0;n<5;n++)manager.link("CODEFORCES","user_"+n);
        assertThrows(ObjectiveException.class,()->manager.link("CODEFORCES","user_6"));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM external_sync_job",Integer.class));
    }
    @Test void publicProofMustMatchAndCanVerifyOnlyOneOwner() throws Exception {
        long first=link();String code=proof(first);ready("club "+code);apply(json.createArrayNode());
        assertEquals(true,own().get("verified"));assertFalse(own().containsKey("proofCode"));
        Map<String,Object> published=accounts(manager.publicView("alice")).get(0);assertFalse(json.writeValueAsString(published).contains(code));assertFalse(published.containsKey("lastError"));
        actor="bob";long second=link();String other=proof(second);ready(other);apply(json.createArrayNode());
        assertEquals(false,own().get("verified"));assertTrue(own().get("verificationNote").toString().contains("其他"));assertTrue(accounts(manager.publicView("bob")).isEmpty());
    }
    @Test void wrongAndExpiredProofsCannotPublishAccount() {
        long id=link();String code=proof(id);ready("wrong");apply(json.createArrayNode());assertEquals(false,own().get("verified"));
        clock+=31*60000L;assertThrows(ObjectiveException.class,()->manager.requestSync(id,true));manager.requestSync(id,false);ready(code);apply(json.createArrayNode());assertEquals(false,own().get("verified"));
    }
    @Test void deletedSiteOwnerCannotReserveVerifiedExternalIdentityForever() {
        long first=link();String firstCode=proof(first);ready(firstCode);apply(json.createArrayNode());
        jdbc.update("DELETE FROM user_info WHERE uuid='alice'");actor="bob";
        long second=link();String secondCode=proof(second);ready(secondCode);apply(json.createArrayNode());
        assertEquals(true,own().get("verified"));assertEquals(1,accounts(manager.publicView("bob")).size());
        assertThrows(ObjectiveException.class,()->manager.publicView("alice"));
    }
    void invalidAuthorityCannotMutateCallback(String ownerChange,String callback) {
        long id=link();Map<String,Object> job=manager.claim();assertNotNull(job);
        if("expired".equals(ownerChange))clock+=90000;
        else if("disabled".equals(ownerChange))jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");
        else jdbc.update("DELETE FROM user_info WHERE uuid='alice'");
        Map<String,Object> beforeAccount=jdbc.queryForMap("SELECT * FROM external_account WHERE id=?",id);
        Map<String,Object> beforeJob=jdbc.queryForMap("SELECT * FROM external_sync_job WHERE account_id=?",id);
        if("apply".equals(callback))manager.apply(job,profile("late profile"));
        else if("failed".equals(callback))manager.failed(job,"late failure");
        else manager.defer(job);
        assertEquals(beforeAccount,jdbc.queryForMap("SELECT * FROM external_account WHERE id=?",id));
        assertEquals(beforeJob,jdbc.queryForMap("SELECT * FROM external_sync_job WHERE account_id=?",id));
    }
    @Test void expiredLeaseWithoutReclaimRejectsApply(){invalidAuthorityCannotMutateCallback("expired","apply");}
    @Test void expiredLeaseWithoutReclaimRejectsFailure(){invalidAuthorityCannotMutateCallback("expired","failed");}
    @Test void expiredLeaseWithoutReclaimRejectsDeferral(){invalidAuthorityCannotMutateCallback("expired","defer");}
    @Test void disabledOwnerRejectsApply(){invalidAuthorityCannotMutateCallback("disabled","apply");}
    @Test void disabledOwnerRejectsFailure(){invalidAuthorityCannotMutateCallback("disabled","failed");}
    @Test void disabledOwnerRejectsDeferral(){invalidAuthorityCannotMutateCallback("disabled","defer");}
    @Test void deletedOwnerRejectsApply(){invalidAuthorityCannotMutateCallback("deleted","apply");}
    @Test void liveLeaseBeforeItsDeadlineStillApplies(){
        long id=link();Map<String,Object> job=manager.claim();clock+=89999;manager.apply(job,profile(""));
        assertEquals(3000,jdbc.queryForObject("SELECT rating FROM external_account WHERE id=?",Integer.class,id));
        assertEquals("RATING",jdbc.queryForObject("SELECT stage FROM external_sync_job WHERE account_id=?",String.class,id));
    }
    ArrayNode publicPage(Map<String,Object> job,int newest) {
        ArrayNode result=json.createArrayNode();int from=((Number)job.get("from_index")).intValue();
        for(int sid=newest-from+1;sid>0 && result.size()<500;sid--)result.add(submission(sid,"OK",10,"A",1200,false));
        return result;
    }
    @Test void shrinkingSourceOffsetsCannotDeleteAnUnseenButStillPublicSubmission() {
        long id=link();ready("");
        jdbc.update("INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES('cf:contest:11:B','CODEFORCES','Cached',1500,'[]',?)",new Timestamp(clock));
        jdbc.update("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(?,1,'cf:contest:11:B','OK',1001,1,'previous-scan')",id);
        Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,501));
        // The source removes its already-read first record, shifting the remaining offsets.
        Map<String,Object> tail=manager.claim();manager.apply(tail,publicPage(tail,500));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=? AND external_id=1",Integer.class,id));
        assertEquals(false,own().get("historyComplete"));assertNull(own().get("lastSuccessAt"));
        assertEquals("PROFILE",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));
        assertNotEquals(first.get("scan_token"),jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class));
    }
    @Test void resumedPaginationDriftReopensTheScanInsteadOfRetryingTheSameStaleOffsetForever() {
        long id=link();ready("");Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,1000));
        Map<String,Object> expired=manager.claim();clock+=90001;Map<String,Object> resumed=manager.claim();
        assertNotEquals(expired.get("lease_token"),resumed.get("lease_token"));
        // Five hundred newer submissions arrived while the worker was stopped.
        try{manager.apply(resumed,publicPage(resumed,1500));}catch(RuntimeException error){manager.failed(resumed,"source page shifted");}
        assertEquals("PROFILE",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));
        assertEquals(500,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
        assertNotEquals(first.get("scan_token"),jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class));
        Map<String,Object> reset=jdbc.queryForMap("SELECT * FROM external_sync_job");
        manager.apply(expired,publicPage(expired,1000));manager.failed(resumed,"late failure");manager.defer(expired);
        assertEquals(reset,jdbc.queryForMap("SELECT * FROM external_sync_job"));assertNull(manager.claim());
        clock+=60001;assertEquals("PROFILE",manager.claim().get("stage"));
    }
    @Test void exactPageBoundariesRequireAStableHeadBeforeCompletingOrPruning() {
        for(int size:new int[]{0,1,499,500,501,999,1000}){
            long id=link();ready("");List<Integer> offsets=new ArrayList<>();Map<String,Object> job;
            while(true){
                job=manager.claim();assertNotNull(job);
                if("VERIFY_STATUS".equals(job.get("stage")))break;
                offsets.add(((Number)job.get("from_index")).intValue());manager.apply(job,publicPage(job,size));
                assertEquals(false,own().get("historyComplete"));assertNull(own().get("lastSuccessAt"));
            }
            assertEquals(size<500?Arrays.asList(1):size<999?Arrays.asList(1,500):Arrays.asList(1,500,999),offsets);
            assertEquals(1,((Number)job.get("from_index")).intValue());manager.apply(job,publicPage(job,size));
            assertEquals(true,own().get("historyComplete"));assertEquals("DONE",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
            assertEquals(size,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));manager.unlink(id);
        }
    }
    @Test void changedHeadCannotPublishACompletedFullScanAndOldVerificationLeaseCannotRetryIt() {
        long id=link();ready("");Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,5));
        Map<String,Object> verify=manager.claim();assertEquals("VERIFY_STATUS",verify.get("stage"));
        manager.apply(verify,publicPage(verify,6));assertEquals("PROFILE",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        assertEquals(false,own().get("historyComplete"));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission",Integer.class));
        assertNull(own().get("lastSuccessAt"));Map<String,Object> reset=jdbc.queryForMap("SELECT * FROM external_sync_job");
        manager.apply(verify,publicPage(verify,5));manager.failed(verify,"late");manager.defer(verify);assertEquals(reset,jdbc.queryForMap("SELECT * FROM external_sync_job"));
    }
    @Test void ghostBoundaryRemainsAnAnchorWithoutBecomingImportedEvidence() {
        long id=link();ready("");Map<String,Object> first=manager.claim();ArrayNode head=publicPage(first,500);
        ((ObjectNode)head.get(499).path("author")).put("ghost",true);manager.apply(first,head);
        Map<String,Object> tail=manager.claim();assertEquals(500,((Number)tail.get("from_index")).intValue());
        manager.apply(tail,page((ObjectNode)head.get(499)));Map<String,Object> verify=manager.claim();manager.apply(verify,head);
        assertEquals(true,own().get("historyComplete"));assertEquals(499,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE external_id=1",Integer.class));
    }
    @Test void verificationFailuresAndProcessRestartRetainTheHeadProofAndOnlyFinalizeOnce() {
        long id=link();ready("");Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,2));
        String hash=jdbc.queryForObject("SELECT cf_head_hash FROM external_sync_job",String.class);assertNotNull(hash);
        Map<String,Object> failed=manager.claim();manager.failed(failed,"temporary failure");assertEquals(false,own().get("historyComplete"));
        assertEquals(hash,jdbc.queryForObject("SELECT cf_head_hash FROM external_sync_job",String.class));clock+=60001;
        Map<String,Object> stopped=manager.claim();clock+=90001;Map<String,Object> resumed=manager.claim();assertNotEquals(stopped.get("lease_token"),resumed.get("lease_token"));
        manager.apply(stopped,publicPage(stopped,3));assertEquals("VERIFY_STATUS",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        manager.apply(resumed,publicPage(resumed,2));Timestamp completed=jdbc.queryForObject("SELECT last_success_at FROM external_account WHERE id=?",Timestamp.class,id);
        manager.apply(resumed,publicPage(resumed,3));assertEquals(completed,jdbc.queryForObject("SELECT last_success_at FROM external_account WHERE id=?",Timestamp.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission",Integer.class));
    }
    @Test void incrementalCompletionAlsoChecksTheHeadWithoutPruningOlderCachedSubmissions() {
        long id=link();ready("");apply(page(submission(1,"OK",10,"A",1200,false)));
        jdbc.update("UPDATE external_sync_job SET stage='STATUS',full_scan=0,checkpoint_id=2000,from_index=1,cf_head_hash=NULL,next_run_at=?",new Timestamp(clock));
        Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,2000));
        Map<String,Object> second=manager.claim();manager.apply(second,publicPage(second,2000));
        assertEquals("VERIFY_STATUS",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        Map<String,Object> verify=manager.claim();manager.apply(verify,publicPage(verify,2000));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=? AND external_id=1",Integer.class,id));
        assertEquals(1000,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
    }
    @Test void legacyInFlightOffsetWithNoHeadProofRestartsWithoutDiscardingCachedRows() {
        long id=link();ready("");Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,1000));
        String scan=jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class);
        jdbc.update("UPDATE external_sync_job SET from_index=501,cf_head_hash=NULL");Map<String,Object> upgraded=manager.claim();
        assertEquals("PROFILE",upgraded.get("stage"));assertEquals(1,((Number)upgraded.get("from_index")).intValue());assertNotEquals(scan,upgraded.get("scan_token"));
        assertEquals(500,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
    }
    @Test void fullPaginationHasDurableCursorAndFailuresKeepPreviouslyImportedEvidence() {
        long id=link();ready("");ArrayNode first=json.createArrayNode();for(int sid=1000;sid>500;sid--)first.add(submission(sid,"OK",10,"A",1200,false));apply(first);
        assertEquals(500,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));assertEquals(false,own().get("historyComplete"));assertEquals(1L,own().get("solved"));
        Map<String,Object> job=manager.claim();manager.failed(job,"暂不可用");assertEquals("BACKOFF",own().get("syncState"));assertEquals(1L,own().get("solved"));assertNull(manager.claim());
        clock+=60001;apply(page(submission(501,"OK",10,"A",1200,false),submission(500,"OK",11,"B",1700,false)));assertEquals(true,own().get("historyComplete"));assertEquals(2L,own().get("solved"));assertEquals("IDLE",own().get("syncState"));assertNull(manager.claim());
        assertEquals(501,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
    }
    @Test void verdictsReconcileAndRemovedSubmissionsPruneOnlyAfterCompleteFullScan() {
        long id=link();String code=proof(id);ready(code);apply(page(submission(9,"OK",10,"A",1500,false),submission(8,"OK",11,"B",1300,false)));
        clock+=900001;ready("");apply(page(submission(9,"WRONG_ANSWER",10,"A",0,false),submission(8,"OK",11,"B",0,false)));assertEquals(1L,own().get("solved"));
        assertEquals(1500,jdbc.queryForObject("SELECT rating FROM external_problem WHERE problem_key='cf:contest:10:A'",Integer.class));
        clock+=86400001;ready("");apply(json.createArrayNode());assertEquals(0L,own().get("solved"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission",Integer.class));
    }
    @Test void malformedPageRollsBackAndRepeatedOrInvalidatedLeaseCannotWrite() {
        long id=link();ready("");Map<String,Object> old=manager.claim();
        ObjectNode invalid=submission(7,"OK",10,"B",1400,false);invalid.put("id",0);
        assertThrows(IllegalArgumentException.class,()->manager.apply(old,page(submission(8,"OK",10,"A",1200,false),invalid)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission",Integer.class));
        clock+=60001;manager.requestSync(id,false);manager.apply(old,page(submission(8,"OK",10,"A",1200,false)));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission",Integer.class));
        Map<String,Object> claimed=manager.claim();manager.unlink(id);manager.apply(claimed,profile(""));assertTrue(accounts(manager.mine()).isEmpty());
    }
    @Test void expiredLeaseCanRecoverAndOldResultCannotClobberNewOwner() {
        link();Map<String,Object> old=manager.claim();clock+=90001;Map<String,Object> current=manager.claim();assertNotEquals(old.get("lease_token"),current.get("lease_token"));
        manager.apply(old,profile(""));assertNull(own().get("rating"));manager.apply(current,profile(""));assertEquals(3000,own().get("rating"));
        manager.apply(current,profile(""));assertEquals("RATING",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
    }
    @Test void registrationIdentityChangeRevokesProofAndRebuildsHistory() {
        long id=link();String code=proof(id);ready(code);apply(page(submission(8,"OK",10,"A",1200,false)));assertEquals(true,own().get("verified"));
        jdbc.update("INSERT INTO remote_user_credential(account_id,credential_ciphertext,updated_at) VALUES(?,'synthetic-old-identity',CURRENT_TIMESTAMP)",id);
        clock+=900001;ArrayNode changed=profile(code);((ObjectNode)changed.get(0)).put("registrationTimeSeconds",999);apply(changed);
        assertEquals(false,own().get("verified"));assertNull(own().get("lastSuccessAt"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission",Integer.class));
        assertTrue(accounts(manager.publicView("alice")).isEmpty());
        assertNull(jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=?",String.class,id));
    }
    @Test void exactSourceIdsDeduplicateWhileTeamsAndGhostsDoNotInflateIndividualCapability() {
        Map<String,Object> local=new HashMap<>();local.put("id",1L);local.put("problem_id","CF-10A");local.put("is_remote",true);local.put("solved",1);
        when(profiles.publicProgrammingEvidence("alice")).thenReturn(Arrays.asList(local));
        long id=link();String code=proof(id);ready(code);ObjectNode ghost=submission(7,"OK",13,"A",3500,false);ghost.with("author").put("ghost",true);
        apply(page(submission(10,"OK",10,"A",1400,false),submission(9,"OK",10,"A",1400,false),submission(8,"OK",12,"B",3000,true),ghost));
        assertEquals(2L,own().get("solved"));assertEquals(1L,own().get("individualSolved"));assertEquals(1400,own().get("representativeRating"));
        assertEquals(2,summary(manager.mine()).get("uniqueSolved"));assertEquals(1L,summary(manager.mine()).get("duplicatesRemoved"));
        assertEquals(1,((List<?>)own().get("ratingHistory")).size());
    }
    @Test void identitiesRequireExplicitKnownFormatsAndNeverMergeSimilarTitles() {
        assertEquals("cf:contest:100001:A1",ProblemIdentity.local(3,"GYM-100001a1",true));assertEquals("hoj:3",ProblemIdentity.local(3,"CF-10A",false));
        assertEquals("hoj:3",ProblemIdentity.local(3,"CF-999999999999999999999999A",true));assertEquals("hoj:3",ProblemIdentity.local(3,"CF-0A",true));
        assertEquals("cf:contest:10:A",ProblemIdentity.codeforces(submission(1,"OK",10,"A",1000,false).path("problem")));
        assertThrows(IllegalArgumentException.class,()->ProblemIdentity.codeforces(json.createObjectNode().put("name","A+B")));
    }
    @Test void explicitSyncRequestsAreThrottledPerAccount() {
        long id=link();assertEquals(429,assertThrows(ObjectiveException.class,()->manager.requestSync(id,false)).getStatus());clock+=60001;manager.requestSync(id,false);
        assertThrows(ObjectiveException.class,()->manager.requestSync(id,false));
    }
}
