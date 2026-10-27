package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class LearningCandidateStoreTest {
    JdbcTemplate jdbc;
    LearningCandidateStore store;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:recommendation_candidates;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE remote_catalog_problem(problem_key VARCHAR(120),contest_id BIGINT,problem_index VARCHAR(16),title VARCHAR(255),rating INT,tags_json TEXT,available BOOLEAN)");
        jdbc.execute("CREATE TABLE remote_nowcoder_problem(problem_key VARCHAR(120),native_problem_id BIGINT,question_id VARCHAR(16),title VARCHAR(255),source_rating INT,available BOOLEAN)");
        jdbc.execute("CREATE TABLE external_atcoder_problem(problem_key VARCHAR(120),task_id VARCHAR(100),contest_id VARCHAR(100),title VARCHAR(255),estimated_difficulty INT,available BOOLEAN)");
        jdbc.execute("CREATE TABLE remote_problem_import(problem_key VARCHAR(120) PRIMARY KEY,problem_id BIGINT,state VARCHAR(16))");
        jdbc.execute("CREATE TABLE problem(id BIGINT,problem_id VARCHAR(100),title VARCHAR(255),rating INT,is_remote BOOLEAN,auth INT,is_group BOOLEAN,gid BIGINT,author VARCHAR(32))");
        jdbc.execute("CREATE TABLE tag(id BIGINT,name VARCHAR(100),gid BIGINT)");jdbc.execute("CREATE TABLE problem_tag(pid BIGINT,tid BIGINT)");
        store=new LearningCandidateStore();ReflectionTestUtils.setField(store,"jdbc",jdbc);ReflectionTestUtils.setField(store,"json",new ObjectMapper());ReflectionTestUtils.setField(store,"catalog",new AlgorithmCatalog());
    }
    @Test void numericCfQuestionIndicesKeepLeadingZerosAndDoNotPreventNormalCandidates() {
        jdbc.update("INSERT INTO remote_catalog_problem VALUES('cf:contest:921:01',921,'01','Numeric task',NULL,'[]',TRUE),('cf:contest:4:A',4,'A','Normal task',800,'[\"math\"]',TRUE)");
        List<LearningCandidateStore.Candidate> rows=store.pool("alice","CODEFORCES");assertEquals(2,rows.size());
        LearningCandidateStore.Candidate numeric=rows.stream().filter(r->r.key.endsWith(":01")).findFirst().get();
        assertEquals("https://codeforces.com/problemset/problem/921/01",numeric.url);assertEquals("CF-92101",numeric.displayId);assertNull(numeric.rating);
        assertTrue(rows.stream().filter(r->r.key.endsWith(":A")).findFirst().get().groups.contains("math"));
    }
    @Test void mismatchedCatalogueIdentityCannotCreateAnUnrelatedSourceLink() {
        jdbc.update("INSERT INTO remote_catalog_problem VALUES('cf:contest:921:01',4,'A','Wrong mapping',800,'[]',TRUE)");
        assertThrows(IllegalStateException.class,()->store.pool("alice","CODEFORCES"));
    }
    @Test void confirmedNowcoderAliasUsesKnownNativeRatingWithoutInventingUnknownMappings() {
        jdbc.update("INSERT INTO remote_nowcoder_problem VALUES('nowcoder:acm:6',6,NULL,'Ordinary',1400,TRUE),('nowcoder:contest:1:A',6,'99','Same native task',NULL,TRUE),('nowcoder:contest:1:B',6,NULL,'Unconfirmed context',NULL,TRUE)");
        List<LearningCandidateStore.Candidate> rows=store.pool("alice","NOWCODER");
        assertEquals(1400,rows.stream().filter(r->r.key.equals("nowcoder:contest:1:A")).findFirst().get().rating);
        assertNull(rows.stream().filter(r->r.key.equals("nowcoder:contest:1:B")).findFirst().get().rating);
        assertEquals("https://ac.nowcoder.com/acm/contest/1/A",rows.stream().filter(r->r.key.equals("nowcoder:contest:1:A")).findFirst().get().url);
    }
    @Test void savedLookupIsBoundAndRechecksAvailabilityWithoutReturningUnrequestedTasks() {
        jdbc.update("INSERT INTO remote_catalog_problem VALUES('cf:contest:4:A',4,'A','Visible',800,'[]',TRUE),('cf:contest:5:A',5,'A','Withdrawn',800,'[]',FALSE)");
        assertEquals(Collections.singleton("cf:contest:4:A"),store.find("alice",Arrays.asList("cf:contest:4:A","cf:contest:5:A")).keySet());
        assertTrue(store.find("alice",Collections.singletonList("cf:' OR 1=1 --")).isEmpty());
        jdbc.update("UPDATE remote_catalog_problem SET available=FALSE"); assertTrue(store.find("alice",Collections.singletonList("cf:contest:4:A")).isEmpty());
        assertTrue(store.find("alice",Collections.emptyList()).isEmpty());
    }
    private void localTables() {
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR \"top.hcode.hoj.manager.oj.LearningCandidateStoreTest.jsonExtract\"");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32),username VARCHAR(32))");jdbc.update("INSERT INTO user_info VALUES('alice','alice')");
        jdbc.execute("CREATE TABLE objective_question(id BIGINT,title VARCHAR(255),content_json TEXT,published BOOLEAN,practice_enabled BOOLEAN,created_by VARCHAR(32))");
    }
    private void donor(long id,String display,long tag,boolean remote,int auth,Boolean group,Long gid) {
        jdbc.update("INSERT INTO problem(id,problem_id,title,rating,is_remote,auth,is_group,gid,author) VALUES(?,?,'Same title',4999,?,?,?,?, 'alice')",id,display,remote,auth,group,gid);
        jdbc.update("INSERT INTO problem_tag VALUES(?,?)",id,tag);
    }
    private LearningCandidateStore.Candidate row(List<LearningCandidateStore.Candidate> values,String key) {
        return values.stream().filter(value->key.equals(value.key)).findFirst().get();
    }
    @Test void atcoderInheritsPublicSameSourceGroupsThroughUniqueCatalogueCaseMapping() {
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc400_a','abc400_a','abc400','Same title',1100,TRUE),('atcoder:task:abc400_b','abc400_b','abc400','Same title',1400,TRUE)");
        jdbc.update("INSERT INTO tag VALUES(1,'dp',NULL),(2,'graphs',NULL),(3,'Unmapped custom label',NULL)");
        donor(1,"AC-ABC400_A",1,true,1,null,null);donor(2,"AC-abc400_a",2,true,1,false,null);donor(3,"AC-abc400_a",3,true,1,false,null);
        List<LearningCandidateStore.Candidate> values=store.pool("alice","ATCODER");
        LearningCandidateStore.Candidate candidate=row(values,"atcoder:task:abc400_a");
        assertEquals(new HashSet<>(Arrays.asList("dp","graph")),candidate.groups);
        assertEquals(1100,candidate.rating);assertEquals("AC-abc400_a",candidate.displayId);assertEquals("https://atcoder.jp/contests/abc400/tasks/abc400_a",candidate.url);
        assertTrue(row(values,"atcoder:task:abc400_b").groups.isEmpty(),"Equal titles never establish identity");
        assertEquals(candidate.groups,store.find("alice",Collections.singleton(candidate.id)).get(candidate.id).groups);
    }
    @Test void nowcoderConfirmedNativeAliasesInheritGroupsWhileUnknownContextsRemainSeparate() {
        jdbc.update("INSERT INTO remote_nowcoder_problem VALUES('nowcoder:acm:6',6,NULL,'Ordinary',1400,TRUE),('nowcoder:contest:1:A',6,'99','Confirmed',NULL,TRUE),('nowcoder:contest:1:B',6,NULL,'Unknown',NULL,TRUE)");
        jdbc.update("INSERT INTO tag VALUES(1,'dp',NULL),(2,'geometry',NULL)");
        donor(1,"NC-6",1,true,1,false,null);donor(2,"NC-1-B",2,true,1,false,null);
        List<LearningCandidateStore.Candidate> values=store.pool("alice","NOWCODER");
        assertEquals(Collections.singleton("dp"),row(values,"nowcoder:acm:6").groups);
        LearningCandidateStore.Candidate confirmed=row(values,"nowcoder:contest:1:A");
        assertEquals(Collections.singleton("dp"),confirmed.groups);assertEquals(1400,confirmed.rating);assertEquals("https://ac.nowcoder.com/acm/contest/1/A",confirmed.url);
        assertEquals(Collections.singleton("geometry"),row(values,"nowcoder:contest:1:B").groups,"Exact context identity may carry its own saved tags, never unconfirmed native tags");
        jdbc.update("UPDATE remote_nowcoder_problem SET question_id=NULL WHERE problem_key='nowcoder:contest:1:A'");
        assertTrue(store.find("alice",Collections.singleton(confirmed.id)).get(confirmed.id).groups.isEmpty());
    }
    @Test void privateGroupNonremoteAndGroupScopedTagsCannotClassifyPublicSourceCandidates() {
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc400_a','abc400_a','abc400','Same title',1100,TRUE)");
        jdbc.update("INSERT INTO tag VALUES(1,'dp',NULL),(2,'geometry',9)");
        donor(1,"AC-abc400_a",1,true,2,false,null);donor(2,"AC-abc400_a",1,true,1,true,null);
        donor(3,"AC-abc400_a",1,true,1,false,9L);donor(4,"AC-abc400_a",1,false,1,false,null);donor(5,"AC-abc400_a",2,true,1,false,null);
        assertTrue(store.pool("alice","ATCODER").get(0).groups.isEmpty());
    }
    @Test void withdrawnVisibilityDeletedTagsAndAmbiguousAtcoderAliasesAreNotCached() {
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc400_a','abc400_a','abc400','Same title',1100,TRUE)");
        jdbc.update("INSERT INTO tag VALUES(1,'dp',NULL)");donor(1,"AC-ABC400_A",1,true,1,false,null);
        assertEquals(Collections.singleton("dp"),store.pool("alice","ATCODER").get(0).groups);
        jdbc.update("UPDATE problem SET auth=2 WHERE id=1");assertTrue(store.pool("alice","ATCODER").get(0).groups.isEmpty());
        jdbc.update("UPDATE problem SET auth=1 WHERE id=1");jdbc.update("DELETE FROM problem_tag WHERE pid=1");
        assertTrue(store.find("alice",Collections.singleton("atcoder:task:abc400_a")).get("atcoder:task:abc400_a").groups.isEmpty());
        jdbc.update("INSERT INTO problem_tag VALUES(1,1)");
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc400_A','abc400_A','abc400','Other case',1100,TRUE)");
        assertTrue(store.pool("alice","ATCODER").stream().allMatch(candidate->candidate.groups.isEmpty()));
    }
    public static String jsonExtract(String document, String path) throws Exception {
        com.fasterxml.jackson.databind.JsonNode value=new ObjectMapper().readTree(document).get(path.substring(2));
        return value==null?null:value.toString();
    }
    @Test void objectiveCandidatesAndSavedLookupKeepTheCurrentPublicTaskRating() throws Exception {
        localTables();jdbc.update("INSERT INTO objective_question VALUES(1,'Rated concept',?,TRUE,TRUE,'coach')","{\"rating\":1200,\"tagIds\":[],\"correctAnswers\":[\"SECRET_ANSWER\"],\"explanation\":\"SECRET_SOLUTION\"}");
        LearningCandidateStore.Candidate candidate=store.pool("alice","HOJ").get(0);
        assertEquals(1200,candidate.rating);assertEquals("OBJECTIVE:1",candidate.id);
        assertFalse(new ObjectMapper().writeValueAsString(candidate).contains("SECRET_"));
        jdbc.update("UPDATE objective_question SET content_json=? WHERE id=1","{\"rating\":1800,\"tagIds\":[]}");
        assertEquals(1800,store.find("alice",Collections.singleton("OBJECTIVE:1")).get("OBJECTIVE:1").rating);
        jdbc.update("UPDATE objective_question SET practice_enabled=FALSE WHERE id=1");
        assertTrue(store.find("alice",Collections.singleton("OBJECTIVE:1")).isEmpty());
    }
    @Test void absentOrInvalidObjectiveRatingsStayUnknownWhileZeroAndUpperBoundRemainValid() {
        localTables();List<String> values=Arrays.asList(null,"null","-1","5001","\"1200\"","12.5","true","0","5000");
        for(int index=0;index<values.size();index++)jdbc.update("INSERT INTO objective_question VALUES(?,?,?,TRUE,TRUE,'coach')",index+1,"Concept "+index,"{\"tagIds\":[]"+(values.get(index)==null?"":",\"rating\":"+values.get(index))+"}");
        List<LearningCandidateStore.Candidate> candidates=store.pool("alice","HOJ");
        for(int index=0;index<7;index++)assertNull(candidates.get(index).rating);
        assertEquals(0,candidates.get(7).rating);assertEquals(5000,candidates.get(8).rating);
    }
}
