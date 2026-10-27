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
    String actor; long clock;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:external_learning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0),('disabled',1)");
        String ddl=StreamUtils.copyToString(new ClassPathResource("external-learning.sql").getInputStream(),StandardCharsets.UTF_8);
        // H2 does not implement MySQL's table collation clause; real MySQL migration is checked separately.
        try(Connection connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ByteArrayResource(ddl.replace(" COLLATE=utf8mb4_unicode_ci", "").getBytes(StandardCharsets.UTF_8)));}
        String credentials=StreamUtils.copyToString(new ClassPathResource("remote-credentials.sql").getInputStream(),StandardCharsets.UTF_8);
        try(Connection connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ByteArrayResource(credentials.replace(" COLLATE=utf8mb4_unicode_ci", "").getBytes(StandardCharsets.UTF_8)));}
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
    void apply(JsonNode result){Map<String,Object> job=manager.claim();assertNotNull(job);manager.apply(job,result);}
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
    @Test void fullPaginationHasDurableCursorAndFailuresKeepPreviouslyImportedEvidence() {
        long id=link();ready("");ArrayNode first=json.createArrayNode();for(int sid=1000;sid>500;sid--)first.add(submission(sid,"OK",10,"A",1200,false));apply(first);
        assertEquals(501,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));assertEquals(false,own().get("historyComplete"));assertEquals(1L,own().get("solved"));
        Map<String,Object> job=manager.claim();manager.failed(job,"暂不可用");assertEquals("BACKOFF",own().get("syncState"));assertEquals(1L,own().get("solved"));assertNull(manager.claim());
        clock+=60001;apply(page(submission(500,"OK",11,"B",1700,false)));assertEquals(true,own().get("historyComplete"));assertEquals(2L,own().get("solved"));assertEquals("IDLE",own().get("syncState"));assertNull(manager.claim());
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
