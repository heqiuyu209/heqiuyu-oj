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
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtCoderManagerTest {
    JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper();ExternalAccountManager accounts;AtCoderEvidenceManager evidence;AtCoderCatalogManager catalogue;LearningProfileManager profiles;
    String actor="alice";long clock=1800000000000L;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:atcoder_learning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("DROP ALL OBJECTS");jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0)");
        for(String file:Arrays.asList("external-learning.sql","remote-sources.sql","atcoder-learning.sql")) {
            String ddl=StreamUtils.copyToString(new ClassPathResource(file).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","").replace("DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND)","CURRENT_TIMESTAMP(3)");
            try(Connection connection=source.getConnection()) {ScriptUtils.executeSqlScript(connection,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        DataSourceTransactionManager tx=new DataSourceTransactionManager(source);
        profiles=mock(LearningProfileManager.class);when(profiles.publicProgrammingEvidence(anyString())).thenReturn(Collections.emptyList());
        evidence=new AtCoderEvidenceManager(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(evidence,"jdbc",jdbc);
        catalogue=new AtCoderCatalogManager(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(catalogue,"jdbc",jdbc);ReflectionTestUtils.setField(catalogue,"transactions",tx);
        accounts=new ExternalAccountManager(){@Override protected long now(){return clock;}@Override protected String uid(){return actor;}};
        ReflectionTestUtils.setField(accounts,"jdbc",jdbc);ReflectionTestUtils.setField(accounts,"transactions",tx);ReflectionTestUtils.setField(accounts,"json",json);ReflectionTestUtils.setField(accounts,"profiles",profiles);ReflectionTestUtils.setField(accounts,"catalog",new AlgorithmCatalog());ReflectionTestUtils.setField(accounts,"atcoder",evidence);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> list(Map<String,Object> snapshot){return (List<Map<String,Object>>)snapshot.get("accounts");}
    Map<String,Object> own(){return list(accounts.mine()).get(0);}
    long link(){return ((Number)list(accounts.link("ATCODER","Tourist")).get(0).get("id")).longValue();}
    ArrayNode profile(String code){return json.createArrayNode().add(json.createObjectNode().put("handle","tourist").put("rating",2800).put("maxRating",3100).put("organization",code));}
    void profile(){accounts.apply(accounts.claim(),profile(""));}
    void rating(){accounts.apply(accounts.claim(),json.createArrayNode());}
    ArrayNode submissions(long first,int count,long second,String verdict){ArrayNode result=json.createArrayNode();for(int n=0;n<count;n++)result.addObject().put("id",first+n).put("epoch_second",second).put("user_id","tourist").put("problem_id","abc100_a").put("result",verdict);return result;}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    ObjectNode problem(String task,String title){return json.createObjectNode().put("id",task).put("contest_id","abc100").put("problem_index","A").put("title",title);}
    void catalog(String source,JsonNode value){jdbc.update("UPDATE remote_source_job SET next_run_at=NULL,lease_token=NULL,lease_until=NULL");jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name=?",new Timestamp(clock),source);catalogue.apply(catalogue.claim(),value);}
    @Test void unverifiedAccountPrivacySourceScopeAndOwnershipArePreserved(){
        long id=link();assertTrue(list(accounts.publicView("alice")).isEmpty());assertEquals("ATCODER",own().get("platform"));
        actor="bob";assertThrows(ObjectiveException.class,()->accounts.unlink(id));actor="alice";
        profile();rating();accounts.apply(accounts.claim(),submissions(1,2,100,"AC"));
        Map<String,Object> view=own();assertEquals(1L,view.get("solved"));assertEquals("ACCOUNT_ACCEPTED",view.get("representativeScope"));assertNull(view.get("individualSolved"));assertEquals(false,view.get("coverageAvailable"));assertEquals(Collections.emptyList(),view.get("coverage"));assertNull(view.get("representativeRating"));assertNull(jdbc.queryForObject("SELECT registration_time FROM external_account",Long.class));
        assertEquals("https://atcoder.jp/users/tourist",view.get("profileUrl"));assertTrue(list(accounts.publicView("alice")).isEmpty());
    }
    @Test void proofUsesAffiliationAndIdentityUniquenessIsPerPlatform(){
        long id=link();String code=(String)list(accounts.proof(id)).get(0).get("proofCode");accounts.apply(accounts.claim(),profile(code));assertEquals(true,own().get("verified"));
        actor="bob";long other=((Number)list(accounts.link("ATCODER","tourist")).get(0).get("id")).longValue();String second=(String)list(accounts.proof(other)).get(0).get("proofCode");
        jdbc.update("UPDATE external_sync_job SET next_run_at=NULL WHERE account_id=?",id);accounts.apply(accounts.claim(),profile(second));assertEquals(false,own().get("verified"));assertNotNull(own().get("verificationNote"));
        accounts.link("CODEFORCES","tourist");assertEquals(3,count("external_account"));actor="alice";accounts.unlink(id);assertEquals(1,count("external_atcoder_cursor"));
    }
    @Test void officialRatedHistoryKeepsStringContestIdsAndFiltersUnratedEvents(){
        link();profile();ArrayNode history=json.createArrayNode();history.addObject().put("IsRated",false);history.addObject().put("IsRated",true).put("Place",12).put("OldRating",1000).put("NewRating",1200).put("ContestName","ARC 061").put("ContestScreenName","arc061.contest.atcoder.jp").put("EndTime","2016-09-11T22:40:00+09:00");
        accounts.apply(accounts.claim(),history);assertEquals(1,count("external_atcoder_rating"));assertEquals(0,count("external_rating_event"));
        assertEquals("arc061",jdbc.queryForObject("SELECT contest_id FROM external_atcoder_rating",String.class));assertEquals(1,own().get("ratedContests"));
        assertTrue(own().get("ratingHistory").toString().contains("https://atcoder.jp/contests/arc061"));
    }
    @Test void timestampBoundaryOverlapsAndExactFiveHundredCanFinishWithoutLosingEvidence(){
        link();profile();rating();accounts.apply(accounts.claim(),submissions(1,500,100,"AC"));assertEquals(100L,jdbc.queryForObject("SELECT from_second FROM external_atcoder_cursor",Long.class));
        accounts.apply(accounts.claim(),submissions(1,500,100,"AC"));Map<String,Object> boundary=accounts.claim();assertEquals("BOUNDARY",boundary.get("stage"));
        accounts.apply(boundary,json.createObjectNode().put("count",500));assertEquals(101L,jdbc.queryForObject("SELECT from_second FROM external_atcoder_cursor",Long.class));
        accounts.apply(accounts.claim(),json.createArrayNode());assertEquals(true,own().get("historyComplete"));assertEquals(500,count("external_submission"));
    }
    @Test void sourceCannotSilentlySkipMoreThanFiveHundredSubmissionsInOneSecond(){
        link();profile();rating();accounts.apply(accounts.claim(),submissions(1,500,100,"AC"));accounts.apply(accounts.claim(),submissions(1,500,100,"AC"));Map<String,Object> job=accounts.claim();
        ObjectiveException error=assertThrows(ObjectiveException.class,()->accounts.apply(job,json.createObjectNode().put("count",501)));accounts.failed(job,error.getMessage());
        assertEquals(false,own().get("historyComplete"));assertEquals("BACKOFF",own().get("syncState"));assertEquals(500,count("external_submission"));assertEquals(100L,jdbc.queryForObject("SELECT from_second FROM external_atcoder_cursor",Long.class));
    }
    @Test void invalidPageRollsBackAndFailedScanNeverPrunesCachedEvidence(){
        link();profile();rating();Map<String,Object> job=accounts.claim();ArrayNode page=submissions(1,2,100,"AC");((ObjectNode)page.get(1)).put("user_id","someone_else");assertThrows(IllegalArgumentException.class,()->accounts.apply(job,page));assertEquals(0,count("external_submission"));
        accounts.apply(job,submissions(1,500,100,"AC"));Map<String,Object> next=accounts.claim();accounts.failed(next,"source offline");assertEquals(500,count("external_submission"));assertEquals(false,own().get("historyComplete"));
    }
    @Test void incrementalRejudgingReconcilesOnlyTheCoveredInterval(){
        long id=link();profile();rating();ArrayNode first=submissions(1,1,100,"AC");first.add(submissions(2,1,300000,"AC").get(0));accounts.apply(accounts.claim(),first);
        clock+=61000;accounts.requestSync(id,false);assertEquals(127200L,jdbc.queryForObject("SELECT from_second FROM external_atcoder_cursor",Long.class));profile();rating();accounts.apply(accounts.claim(),submissions(2,1,300000,"WA"));
        assertEquals(2,count("external_submission"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE verdict='OK'",Integer.class));assertEquals(true,own().get("historyComplete"));
        clock+=61000;accounts.requestSync(id,false);profile();rating();accounts.apply(accounts.claim(),json.createArrayNode());assertEquals(1,count("external_submission"));
    }
    @Test void resettingOrUnlinkingInvalidatesInFlightResponses(){
        long id=link();Map<String,Object> stale=accounts.claim();clock+=61000;accounts.requestSync(id,false);accounts.apply(stale,profile(""));assertNull(own().get("profileSyncedAt"));
        stale=accounts.claim();accounts.unlink(id);accounts.apply(stale,profile(""));assertEquals(0,count("external_atcoder_cursor"));assertEquals(0,count("external_atcoder_rating"));
    }
    @Test void metadataPublishesExactIdentitiesAndExplicitUnknownEstimates(){
        catalog("ATCODER_PROBLEMS",json.createArrayNode().add(problem("abc100_a","A. Example")).add(problem("abc100_b","B. Unknown")).add(problem("abc100_c","C. Experimental")));
        ObjectNode models=json.createObjectNode();models.putObject("abc100_a").put("difficulty",-848).put("is_experimental",false);models.putObject("abc100_c").put("difficulty",2000).put("is_experimental",true);catalog("ATCODER_MODELS",models);
        assertEquals(18,jdbc.queryForObject("SELECT rating FROM external_problem WHERE problem_key='atcoder:task:abc100_a'",Integer.class));assertNull(jdbc.queryForObject("SELECT rating FROM external_problem WHERE problem_key='atcoder:task:abc100_b'",Integer.class));assertNull(jdbc.queryForObject("SELECT rating FROM external_problem WHERE problem_key='atcoder:task:abc100_c'",Integer.class));
        link();profile();rating();accounts.apply(accounts.claim(),submissions(1,1,100,"AC"));assertEquals(18,own().get("representativeRating"));assertEquals("A. Example",jdbc.queryForObject("SELECT title FROM external_problem WHERE problem_key='atcoder:task:abc100_a'",String.class));
    }
    @Test void catalogueAndModelSnapshotsAreAtomicAndRemovedTasksRetainUnknownEvidence(){
        catalog("ATCODER_PROBLEMS",json.createArrayNode().add(problem("abc100_a","Original")));catalog("ATCODER_MODELS",json.createObjectNode().set("abc100_a",json.createObjectNode().put("difficulty",1200).put("is_experimental",false)));
        assertThrows(IllegalArgumentException.class,()->catalog("ATCODER_PROBLEMS",json.createArrayNode().add(problem("abc100_a","New")).add(problem("ABC100_A","Ambiguous"))));assertEquals("Original",jdbc.queryForObject("SELECT title FROM external_atcoder_problem",String.class));
        ObjectNode bad=json.createObjectNode();bad.putObject("abc100_a").put("difficulty",1300).put("is_experimental",false);bad.putObject("abc100_b").put("difficulty","unknown").put("is_experimental",false);assertThrows(IllegalArgumentException.class,()->catalog("ATCODER_MODELS",bad));assertEquals(1200,jdbc.queryForObject("SELECT rating FROM external_problem",Integer.class));
        catalog("ATCODER_PROBLEMS",json.createArrayNode().add(problem("abc100_b","New task")));assertEquals(2,count("external_atcoder_problem"));assertNull(jdbc.queryForObject("SELECT rating FROM external_problem WHERE problem_key='atcoder:task:abc100_a'",Integer.class));
    }
    @Test void legacyLocalAtCoderIdsDeduplicateOnlyAgainstExplicitCanonicalCatalogueIds(){
        catalog("ATCODER_PROBLEMS",json.createArrayNode().add(problem("tenka1_2012_qualA_4","D. Example")));
        Map<String,Object> local=new HashMap<>();local.put("id",7L);local.put("problem_id","AC-tenka1_2012_quala_4");local.put("is_remote",1);local.put("solved",1);when(profiles.publicProgrammingEvidence("alice")).thenReturn(Collections.singletonList(local));
        long id=link();jdbc.update("UPDATE external_account SET verified_identity=handle_key WHERE id=?",id);profile();rating();ArrayNode page=submissions(1,1,100,"AC");((ObjectNode)page.get(0)).put("problem_id","tenka1_2012_qualA_4");accounts.apply(accounts.claim(),page);
        @SuppressWarnings("unchecked") Map<String,Object> summary=(Map<String,Object>)accounts.publicView("alice").get("summary");assertEquals(1,summary.get("uniqueSolved"));assertEquals(1L,summary.get("duplicatesRemoved"));
        assertEquals("atcoder:task:tenka1_2012_qualA_4",ProblemIdentity.atcoder("tenka1_2012_qualA_4"));assertEquals("hoj:7",ProblemIdentity.local(7,"AC-abc100_a",false));
    }
}
