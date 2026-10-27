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

class NowCoderManagerTest {
    JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper();ExternalAccountManager accounts;NowCoderEvidenceManager evidence;LearningProfileManager profiles;
    String actor="alice";long clock=1800000000000L;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:nowcoder_learning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(source);jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0)");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(100),is_remote BOOLEAN)");
        jdbc.execute("CREATE TABLE remote_problem_import(problem_key VARCHAR(120) PRIMARY KEY,problem_id BIGINT)");
        for(String file:Arrays.asList("external-learning.sql","nowcoder-learning.sql")){
            String ddl=StreamUtils.copyToString(new ClassPathResource(file).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","");
            try(Connection connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        profiles=mock(LearningProfileManager.class);when(profiles.publicProgrammingEvidence(anyString())).thenReturn(Collections.emptyList());
        evidence=new NowCoderEvidenceManager(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(evidence,"jdbc",jdbc);
        accounts=new ExternalAccountManager(){@Override protected long now(){return clock;}@Override protected String uid(){return actor;}};
        ReflectionTestUtils.setField(accounts,"jdbc",jdbc);ReflectionTestUtils.setField(accounts,"transactions",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(accounts,"json",json);ReflectionTestUtils.setField(accounts,"profiles",profiles);ReflectionTestUtils.setField(accounts,"catalog",new AlgorithmCatalog());ReflectionTestUtils.setField(accounts,"nowcoder",evidence);
        NowCoderContextManager contexts=mock(NowCoderContextManager.class);when(contexts.view(anyLong(),anyBoolean())).thenReturn(Collections.singletonMap("available",false));ReflectionTestUtils.setField(accounts,"nowcoderContexts",contexts);
        NowCoderPracticeManager practice=mock(NowCoderPracticeManager.class);when(practice.view(anyLong(),anyBoolean())).thenReturn(Collections.singletonMap("available",false));ReflectionTestUtils.setField(accounts,"nowcoderPractice",practice);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> list(Map<String,Object> snapshot){return (List<Map<String,Object>>)snapshot.get("accounts");}
    Map<String,Object> own(){return list(accounts.mine()).get(0);}
    long link(){return ((Number)list(accounts.link("NOWCODER","12345")).get(0).get("id")).longValue();}
    ArrayNode profile(String code){return json.createArrayNode().add(json.createObjectNode().put("handle","12345").put("displayName","测试名称").put("rating",947).put("organization",code));}
    void start(String proof){accounts.apply(accounts.claim(),profile(proof));accounts.apply(accounts.claim(),json.createArrayNode());}
    ObjectNode page(int total,int from,long top){
        ObjectNode page=json.createObjectNode().put("uid","12345").put("page",from).put("totalPages",(total+49)/50).put("attempted",total==0?0:1).put("solved",total==0?0:1).put("submissions",total);
        ArrayNode items=page.putArray("items");for(int n=(from-1)*50;n<Math.min(total,from*50);n++)items.addObject().put("id",top-n).put("problemKey","nowcoder:acm:218144").put("title","Sample").put("verdict","OK").put("submittedAt",1000-n);return page;
    }
    void finish(int total,long top){for(int n=1;n<=Math.max(1,(total+49)/50);n++)accounts.apply(accounts.claim(),page(total,n,top));accounts.apply(accounts.claim(),page(total,1,top));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void sync(boolean full){clock+=90001;accounts.requestSync(((Number)own().get("id")).longValue(),false);if(full)jdbc.update("UPDATE external_sync_job SET full_scan=1");start("");}
    @Test void numericBindingOwnershipPrivacyAndVerifiedPublicScope(){
        assertThrows(ObjectiveException.class,()->accounts.link("NOWCODER","myname"));assertThrows(ObjectiveException.class,()->accounts.link("NOWCODER","00123"));
        long id=link();assertTrue(list(accounts.publicView("alice")).isEmpty());actor="bob";assertThrows(ObjectiveException.class,()->accounts.unlink(id));actor="alice";
        String code=(String)list(accounts.proof(id)).get(0).get("proofCode");start(code);assertEquals(true,own().get("verified"));assertEquals("测试名称",own().get("displayName"));assertEquals("NUMERIC_USER_ID",own().get("identityContinuity"));assertNull(jdbc.queryForObject("SELECT registration_time FROM external_account",Long.class));
        assertEquals(1,list(accounts.publicView("alice")).size());assertFalse(list(accounts.publicView("alice")).get(0).containsKey("proofCode"));assertEquals("https://ac.nowcoder.com/acm/contest/profile/12345",own().get("profileUrl"));
    }
    @Test void onlyTheOwnerCanRequestBothCodingAndContestContextRefresh(){
        long id=link();NowCoderContextManager contexts=(NowCoderContextManager)ReflectionTestUtils.getField(accounts,"nowcoderContexts");
        actor="bob";assertThrows(ObjectiveException.class,()->accounts.requestSync(id,false));verify(contexts,never()).request(anyLong());
        actor="alice";clock+=90001;accounts.requestSync(id,false);verify(contexts).request(id);
    }
    @Test void completeStablePagesRecheckHeadBeforePublishingAndKeepUnknownDifficulty(){
        link();start("");accounts.apply(accounts.claim(),page(51,1,100));assertEquals(false,own().get("historyComplete"));accounts.apply(accounts.claim(),page(51,2,100));assertEquals("VERIFY",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));assertNull(own().get("lastSuccessAt"));
        accounts.apply(accounts.claim(),page(51,1,100));assertEquals(51,count("external_submission"));assertEquals(true,own().get("historyComplete"));assertEquals(1L,own().get("solved"));assertNull(own().get("individualSolved"));assertNull(own().get("representativeRating"));assertEquals(false,own().get("coverageAvailable"));assertEquals("PUBLIC_PROFILE_CODING",own().get("statisticsScope"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE individual=1",Integer.class));
    }
    @Test void movedOffsetPagesRestartWithoutDeletingCachedHistory(){
        link();start("");finish(1,10);sync(true);accounts.apply(accounts.claim(),page(51,1,100));accounts.apply(accounts.claim(),page(52,2,101));assertEquals(1,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));assertEquals("STATUS",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE external_id=10",Integer.class));assertNotNull(own().get("lastSuccessAt"));
    }
    @Test void headRecheckDetectsChangedRecordsAndOnlyStableFullScanRemovesDeletedRows(){
        link();start("");finish(1,10);sync(true);accounts.apply(accounts.claim(),page(1,1,20));accounts.apply(accounts.claim(),page(1,1,21));assertEquals(2,count("external_submission"));assertEquals("STATUS",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        clock+=30001;accounts.apply(accounts.claim(),page(1,1,21));accounts.apply(accounts.claim(),page(1,1,21));assertEquals(1,count("external_submission"));assertEquals(21L,jdbc.queryForObject("SELECT external_id FROM external_submission",Long.class));
    }
    @Test void olderDeletionOutsideIncrementalWindowRequiresFullReconciliation(){
        link();start("");finish(51,100);sync(false);accounts.apply(accounts.claim(),page(50,1,101));accounts.apply(accounts.claim(),page(50,1,101));assertEquals(true,ExternalAccountManager.flag(jdbc.queryForObject("SELECT full_scan FROM external_sync_job",Object.class)));assertEquals("STATUS",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));assertEquals(52,count("external_submission"));
        clock+=30001;finish(50,101);assertEquals(50,count("external_submission"));assertEquals(1L,own().get("solved"));
    }
    @Test void explicitEmptyHistoryCanCompleteWithoutInventingRatings(){
        link();start("");finish(0,0);assertEquals(true,own().get("historyComplete"));assertEquals(0L,own().get("solved"));assertNull(own().get("maxRating"));assertEquals(0,count("external_submission"));
    }
    @Test void expiredLeaseAndBusyProviderDeferralCannotPublishLateResults(){
        long id=link();Map<String,Object> old=accounts.claim();clock+=90001;Map<String,Object> current=accounts.claim();accounts.apply(old,profile(""));assertNull(own().get("displayName"));accounts.defer(old);assertEquals(current.get("lease_token"),jdbc.queryForObject("SELECT lease_token FROM external_sync_job",String.class));accounts.defer(current);assertNull(jdbc.queryForObject("SELECT lease_token FROM external_sync_job",String.class));assertEquals(0,jdbc.queryForObject("SELECT failures FROM external_sync_job",Integer.class));
        assertEquals(1,count("external_nowcoder_profile"));accounts.unlink(id);assertEquals(0,count("external_nowcoder_profile"));
    }
    @Test void verifiedExplicitSourceIdDeduplicatesLocalButNeverSameTitle(){
        long id=link();start((String)list(accounts.proof(id)).get(0).get("proofCode"));finish(1,99);
        Map<String,Object> local=new HashMap<>();local.put("id",1L);local.put("problem_id","NC-218144");local.put("is_remote",true);local.put("solved",1);when(profiles.publicProgrammingEvidence("alice")).thenReturn(Arrays.asList(local));
        Map<String,Object> summary=(Map<String,Object>)accounts.mine().get("summary");assertEquals(1,summary.get("uniqueSolved"));assertEquals(1L,summary.get("duplicatesRemoved"));local.put("is_remote",false);assertEquals(2,((Map<?,?>)accounts.mine().get("summary")).get("uniqueSolved"));
    }
    @Test void ratingHistoryRetainsOfficialScaleAndLinks(){
        link();accounts.apply(accounts.claim(),profile(""));ArrayNode ratings=json.createArrayNode();ratings.addObject().put("contestId",99785).put("contestName","Sample contest").put("rank",1128).put("oldRating",907).put("newRating",947).put("ratingUpdateTimeSeconds",1737111600L);accounts.apply(accounts.claim(),ratings);
        assertEquals(947,own().get("maxRating"));assertEquals(1,own().get("ratedContests"));assertEquals("AVAILABLE_RATED_HISTORY",own().get("maximumRatingScope"));assertTrue(own().get("ratingHistory").toString().contains("https://ac.nowcoder.com/acm/contest/99785"));
    }
    @Test void submissionsTiedAtOneSecondNeedNotHaveDescendingIds(){
        link();start("");ObjectNode rows=page(3,1,100);ArrayNode items=(ArrayNode)rows.path("items");
        ((ObjectNode)items.get(0)).put("id",98).put("submittedAt",1000);((ObjectNode)items.get(1)).put("id",100).put("submittedAt",1000);((ObjectNode)items.get(2)).put("id",99).put("submittedAt",1000);
        accounts.apply(accounts.claim(),rows);accounts.apply(accounts.claim(),rows);assertEquals(true,own().get("historyComplete"));assertEquals(3,count("external_submission"));
    }
}
