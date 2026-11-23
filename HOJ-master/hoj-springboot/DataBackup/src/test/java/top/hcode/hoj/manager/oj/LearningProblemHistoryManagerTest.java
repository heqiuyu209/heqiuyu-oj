package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.LearningHistoryDTO;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningProblemHistoryManagerTest {
    JdbcTemplate jdbc; LearningProfileManager profiles; AiAccessGuard access;
    LearningProblemHistoryManager manager; AlgorithmCatalog catalog = new AlgorithmCatalog();
    ObjectMapper json = new ObjectMapper(); String actor = "alice"; long clock = 1800000000000L;

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:problem_history;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(100),is_remote BOOLEAN,auth INT,is_group BOOLEAN,gid BIGINT)");
        jdbc.execute("CREATE TABLE tag(id BIGINT PRIMARY KEY,name VARCHAR(100),gid BIGINT)");jdbc.execute("CREATE TABLE problem_tag(pid BIGINT,tid BIGINT)");
        for (String file : Arrays.asList("external-learning.sql", "remote-sources.sql", "atcoder-learning.sql", "nowcoder-learning.sql", "nowcoder-sources.sql")) {
            String sql = StreamUtils.copyToString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)
                    .replace(" COLLATE=utf8mb4_unicode_ci", "").replace("DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND)", "CURRENT_TIMESTAMP(3)");
            try (Connection c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        }
        profiles = mock(LearningProfileManager.class); access = mock(AiAccessGuard.class);
        when(profiles.publicProgrammingDetails(anyString())).thenReturn(Collections.emptyList());
        when(profiles.publicProgrammingEvidence(anyString())).thenReturn(Collections.emptyList());
        when(profiles.publicProgrammingGroups(anyString())).thenReturn(Collections.emptyMap());
        manager = new LearningProblemHistoryManager() { @Override protected String uid() { return actor; } @Override protected long now() { return clock; } };
        ReflectionTestUtils.setField(manager,"jdbc",jdbc); ReflectionTestUtils.setField(manager,"profiles",profiles);
        ReflectionTestUtils.setField(manager,"catalog",catalog); ReflectionTestUtils.setField(manager,"json",json); ReflectionTestUtils.setField(manager,"access",access);
    }
    Map<String,Object> row(Object... pairs) { Map<String,Object> result = new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]); return result; }
    void account(long id,String source,boolean verified) {
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at,proof_code) VALUES(?,'alice',?,?,?,?,1,?,?,?,'DO_NOT_EXPOSE_PROOF')",id,source,"account"+id,"account"+id,verified?"account"+id:null,new Timestamp(clock),new Timestamp(clock),new Timestamp(clock));
        jdbc.update("INSERT INTO external_sync_job(account_id,scan_token,stage) VALUES(?,'fixture','DONE')",id);
    }
    void submission(long account,long id,String source,String key,boolean solved,boolean individual,long seconds) {
        jdbc.update("INSERT IGNORE INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,'Same title',1300,'[\"math\"]',?)",key,source,new Timestamp(clock));
        jdbc.update("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(?,?,?,?,?,?,'fixture')",account,id,key,solved?"OK":"WRONG_ANSWER",seconds,individual);
    }
    void cf(String key,int contest,String index,Integer rating) {
        jdbc.update("INSERT INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,rating,tags_json,content_hash,seen_token,last_seen_at,changed_at) VALUES(?,?,?,'Catalogue title',?,'[\"dp\"]',?,'fixture',?,?)",key,contest,index,rating,String.join("",Collections.nCopies(64,"a")),new Timestamp(clock),new Timestamp(clock));
    }
    Map<String,Object> local(long id,String display,boolean remote,boolean solved,long last) {
        return row("id",id,"problem_id",display,"is_remote",remote,"title","Local title","rating",1600,"solved",solved?1:0,"submissions",3L,"first_at",new Timestamp(1000),"last_at",new Timestamp(last),"first_solved_at",solved?new Timestamp(2000):null);
    }
    Map<String,Object> list() { return manager.list(new LearningHistoryDTO.Query()); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("items"); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> entries(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("entries"); }
    @SuppressWarnings("unchecked") Map<String,Object> totals(Map<String,Object> result) { return (Map<String,Object>)result.get("totals"); }

    @Test void canonicalMergeRetainsOriginalRatingsCountsAndTimeAndReconcilesChart() {
        account(1,"CODEFORCES",true); account(2,"CODEFORCES",true); cf("cf:contest:4:A",4,"A",800);
        submission(1,1,"CODEFORCES","cf:contest:4:A",false,true,4); submission(1,2,"CODEFORCES","cf:contest:4:A",true,true,6);
        submission(2,1,"CODEFORCES","cf:contest:4:A",true,true,8);
        List<Map<String,Object>> local=Collections.singletonList(local(7,"CF-4A",true,true,3000));
        when(profiles.publicProgrammingDetails("alice")).thenReturn(local); when(profiles.publicProgrammingEvidence("alice")).thenReturn(local);
        Map<String,Object> result=list(); assertEquals(1,items(result).size()); Map<String,Object> item=items(result).get(0);
        assertEquals("cf:contest:4:A",item.get("identity")); assertEquals(3,entries(item).size());
        assertEquals(1600,entries(item).get(0).get("rating")); assertEquals("HOJ_RATING",entries(item).get(0).get("difficultyMetric"));
        Map<String,Object> cf=entries(item).get(1); assertEquals(800,cf.get("rating")); assertEquals(2L,cf.get("submissions"));
        assertEquals(4000L,cf.get("firstAttemptAt")); assertEquals(6000L,cf.get("firstSolvedAt")); assertEquals("account1",cf.get("handle"));
        assertEquals(8000L,item.get("lastAttemptAt")); assertEquals(2L,totals(result).get("duplicatesRemoved"));
        Map<String,Object> metrics=row("objectiveAttempted",0L,"objectiveSolved",0L,"attempted",0L,"solved",0L);
        List<Map<String,Object>> axes=new ArrayList<>(); catalog.groups().forEach((code,name)->{Map<String,Object> axis=new LinkedHashMap<>(metrics);axis.put("code",code);axes.add(axis);});
        when(profiles.profile("alice")).thenReturn(row("overall",metrics,"axes",axes));
        LearningEvidenceManager evidence=new LearningEvidenceManager(); ReflectionTestUtils.setField(evidence,"jdbc",jdbc);ReflectionTestUtils.setField(evidence,"profiles",profiles);ReflectionTestUtils.setField(evidence,"catalog",catalog);ReflectionTestUtils.setField(evidence,"json",json);
        Map<?,?> overall=(Map<?,?>)((Map<?,?>)evidence.profile("alice").get("combined")).get("overall");
        for(String name:Arrays.asList("attempted","solved","duplicatesRemoved"))assertEquals(((Number)overall.get(name)).longValue(),((Number)totals(result).get(name)).longValue());
    }
    @Test void excludesUnverifiedAndTeamAndOtherOwnersAndNeverMergesTitles() throws Exception {
        account(1,"CODEFORCES",true);account(2,"CODEFORCES",false);
        submission(1,1,"CODEFORCES","cf:contest:1:A",true,false,10);submission(2,1,"CODEFORCES","cf:contest:2:A",true,true,10);
        submission(1,2,"CODEFORCES","cf:contest:3:A",false,true,10);submission(1,3,"CODEFORCES","cf:contest:4:A",true,true,10);
        Map<String,Object> result=list();assertEquals(2,items(result).size());assertEquals(1L,totals(result).get("solved"));
        String text=json.writeValueAsString(result);for(String secret:Arrays.asList("DO_NOT_EXPOSE_PROOF","account_id","proofCode","cookie","correctAnswers"))assertFalse(text.contains(secret));
        actor="bob";assertTrue(items(list()).isEmpty());verify(profiles).publicProgrammingDetails("bob");verify(access,times(2)).requireActiveUser("bob");verify(access,never()).requireChat(anyString());
    }
    @Test void confirmedNowcoderAliasKeepsBothOriginalLinksAndUnknownAliasSeparate() {
        account(1,"NOWCODER",true);
        jdbc.update("INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,question_id,title,content_hash,last_seen_at,changed_at) VALUES('nowcoder:contest:99:A',123,'456','Alias',?, ?,?)",String.join("",Collections.nCopies(64,"a")),new Timestamp(clock),new Timestamp(clock));
        submission(1,1,"NOWCODER","nowcoder:contest:99:A",true,true,10);submission(1,2,"NOWCODER","nowcoder:acm:123",true,true,10);submission(1,3,"NOWCODER","nowcoder:contest:99:B",true,true,10);
        Map<String,Object> result=list();assertEquals(2,items(result).size());Map<String,Object> merged=items(result).stream().filter(item->"nowcoder:acm:123".equals(item.get("identity"))).findFirst().get();
        assertEquals(2,entries(merged).size());assertTrue(entries(merged).stream().anyMatch(item->String.valueOf(item.get("url")).contains("/acm/contest/99/A")));assertTrue(entries(merged).stream().anyMatch(item->String.valueOf(item.get("url")).contains("/acm/problem/123")));
        assertNull(entries(merged).get(0).get("rating"));assertEquals(1L,totals(result).get("duplicatesRemoved"));
    }
    @Test void atcoderCaseUsesConfirmedTaskMetadataAndNeverInventsMissingRatingsOrTimes() {
        account(1,"ATCODER",true);
        jdbc.update("INSERT INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,content_hash,seen_token,last_seen_at,changed_at,estimated_difficulty) VALUES('atcoder:task:abc086_A','abc086_A','abc086','A','Product',?,'fixture',?,?,900)",String.join("",Collections.nCopies(64,"a")),new Timestamp(clock),new Timestamp(clock));
        submission(1,1,"ATCODER","atcoder:task:abc086_a",true,true,0);submission(1,2,"ATCODER","atcoder:task:abc086_A",true,true,2);
        Map<String,Object> item=items(list()).get(0);assertEquals(2,entries(item).size());assertEquals("atcoder:task:abc086_A",item.get("identity"));
        assertTrue(entries(item).stream().allMatch(e->"https://atcoder.jp/contests/abc086/tasks/abc086_A".equals(e.get("url"))));
        Map<String,Object> unknown=entries(item).stream().filter(e->"atcoder:task:abc086_a".equals(e.get("problemKey"))).findFirst().get();assertNull(unknown.get("firstAttemptAt"));assertEquals(900,unknown.get("rating"));
        jdbc.update("UPDATE external_atcoder_problem SET available=0");assertTrue(entries(items(list()).get(0)).stream().allMatch(e->e.get("rating")==null));
    }
    @Test void filtersSourceStatusAndDuplicatesBeforePagingAndKeepsGlobalTotals() {
        account(1,"CODEFORCES",true);submission(1,1,"CODEFORCES","cf:contest:4:A",true,true,2);
        when(profiles.publicProgrammingDetails("alice")).thenReturn(Collections.singletonList(local(7,"CF-4A",true,false,1000)));
        LearningHistoryDTO.Query query=new LearningHistoryDTO.Query();query.setOnlyDuplicates(true);query.setSource("HOJ");query.setStatus("SOLVED");assertEquals(0,manager.list(query).get("total"));
        query.setStatus("ATTEMPTED");Map<String,Object> result=manager.list(query);assertEquals(1,result.get("total"));assertEquals(false,items(result).get(0).get("solved"));assertEquals(1,entries(items(result).get(0)).size());assertEquals(2,items(result).get(0).get("recordCount"));assertEquals(1L,totals(result).get("solved"));
        query.setKeyword("NO SUCH TITLE");assertEquals(0,manager.list(query).get("total"));query.setKeyword("");query.setGroupCode("math");assertEquals(1,manager.list(query).get("total"));
    }
    @Test void paginationIsStableAndSearchIsNotLimitedToTheFirstPage() {
        List<Map<String,Object>> local=new ArrayList<>();for(int i=1;i<=25;i++)local.add(local(i,"P"+i,false,true,1000));when(profiles.publicProgrammingDetails("alice")).thenReturn(local);
        Map<String,Object> first=list();LearningHistoryDTO.Query query=new LearningHistoryDTO.Query();query.setPage(2);Map<String,Object> second=manager.list(query);assertEquals(25,first.get("total"));assertEquals(20,items(first).size());assertEquals(5,items(second).size());
        Set<Object> ids=new HashSet<>();items(first).forEach(item->ids.add(item.get("identity")));items(second).forEach(item->assertTrue(ids.add(item.get("identity"))));
        query.setPage(1);query.setKeyword("P9");assertEquals(1,manager.list(query).get("total"));query.setPage(3);assertTrue(items(manager.list(query)).isEmpty());
    }
    @Test void unavailableCatalogueUsesOnlyTheDocumentedSourceFallbackAndShowsIncompleteFreshness() {
        account(1,"CODEFORCES",true);submission(1,1,"CODEFORCES","cf:contest:4:A",true,true,1);
        jdbc.update("UPDATE external_account SET history_complete=0,last_success_at=NULL");jdbc.update("UPDATE external_sync_job SET failures=1");
        Map<String,Object> result=list(),entry=entries(items(result).get(0)).get(0);assertEquals(1300,entry.get("rating"));assertEquals(false,entry.get("metadataAvailable"));assertNull(entry.get("url"));assertEquals(false,result.get("historyComplete"));assertEquals(true,result.get("stale"));
        jdbc.update("UPDATE external_problem SET rating=NULL");assertNull(entries(items(list()).get(0)).get(0).get("rating"));
    }
    @Test void numericCfTaskDoesNotBreakTheWholePersonalHistoryPage() {
        account(1,"CODEFORCES",true);cf("cf:contest:921:01",921,"01",3200);cf("cf:contest:4:A",4,"A",800);
        submission(1,1,"CODEFORCES","cf:contest:921:01",true,true,2);
        submission(1,2,"CODEFORCES","cf:contest:4:A",true,true,1);
        Map<String,Object> result=list();assertEquals(2,items(result).size());
        Map<String,Object> numeric=items(result).stream().filter(item->"cf:contest:921:01".equals(item.get("identity"))).findFirst().get();
        assertEquals("https://codeforces.com/problemset/problem/921/01",entries(numeric).get(0).get("url"));
        assertEquals(3200,entries(numeric).get(0).get("rating"));assertEquals(2L,totals(result).get("solved"));
    }
    @Test void publishedNumericCopyAndVerifiedExternalRecordShareOneIdentity() {
        account(1,"CODEFORCES",true);cf("cf:contest:921:01",921,"01",3200);
        submission(1,1,"CODEFORCES","cf:contest:921:01",true,true,2);
        jdbc.update("INSERT INTO problem(id,problem_id,is_remote) VALUES(7,'CF-92101',TRUE)");
        jdbc.update("INSERT INTO remote_problem_import(problem_key,requested_by,requested_author,last_requested_at,problem_id,state) VALUES('cf:contest:921:01','alice','alice',?,7,'FAILED')",new Timestamp(clock));
        when(profiles.publicProgrammingDetails("alice")).thenReturn(Collections.singletonList(local(7,"CF-92101",true,true,1000)));
        Map<String,Object> result=list();assertEquals(1,items(result).size());
        Map<String,Object> merged=items(result).get(0);assertEquals("cf:contest:921:01",merged.get("identity"));
        assertEquals(2,entries(merged).size());assertEquals(1L,totals(result).get("duplicatesRemoved"));
        jdbc.update("DELETE FROM remote_problem_import WHERE problem_id=7");
        assertEquals(2,items(list()).size());
    }
    @Test void rejectsMalformedFiltersBeforeReadingPrivateEvidence() {
        List<LearningHistoryDTO.Query> bad=new ArrayList<>();LearningHistoryDTO.Query query=new LearningHistoryDTO.Query();query.setSource("SQL");bad.add(query);query=new LearningHistoryDTO.Query();query.setStatus("PASSED");bad.add(query);query=new LearningHistoryDTO.Query();query.setPage(0);bad.add(query);query=new LearningHistoryDTO.Query();query.setGroupCode("future-unknown");bad.add(query);query=new LearningHistoryDTO.Query();query.setKeyword(String.join("",Collections.nCopies(101,"a")));bad.add(query);
        for(LearningHistoryDTO.Query invalid:bad)assertEquals(400,assertThrows(ObjectiveException.class,()->manager.list(invalid)).getStatus());verifyNoInteractions(profiles);
    }
    @Test void externalOnlyDirectionsUseTheSamePublicSourceClassificationAndWithdrawWithoutCachedTags() {
        account(1,"ATCODER",true);account(2,"NOWCODER",true);
        submission(1,1,"ATCODER","atcoder:task:abc400_a",true,false,10);submission(2,1,"NOWCODER","nowcoder:acm:12",true,false,20);
        String h=String.join("",Collections.nCopies(64,"a"));
        jdbc.update("INSERT INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,content_hash,seen_token,last_seen_at,changed_at,estimated_difficulty) VALUES('atcoder:task:abc400_A','abc400_A','abc400','A','Known task',?,'fixture',?,?,900)",h,new Timestamp(clock),new Timestamp(clock));
        jdbc.update("INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,title,source_rating,content_hash,last_seen_at,changed_at) VALUES('nowcoder:acm:12',12,'Known task',1100,?,?,?)",h,new Timestamp(clock),new Timestamp(clock));
        jdbc.update("INSERT INTO problem VALUES(1,'AC-ABC400_A',TRUE,1,FALSE,NULL),(2,'NC-12',TRUE,1,FALSE,NULL)");
        jdbc.update("INSERT INTO tag VALUES(1,'dp',NULL)");jdbc.update("INSERT INTO problem_tag VALUES(1,1),(2,1)");
        LearningHistoryDTO.Query query=new LearningHistoryDTO.Query();query.setGroupCode("dp");
        Map<String,Object> result=manager.list(query);assertEquals(2,result.get("total"));assertEquals(2L,totals(result).get("sourceRecords"));
        assertTrue(items(result).stream().allMatch(item->entries(item).size()==1));
        query.setSource("HOJ");assertEquals(0,manager.list(query).get("total"));query.setSource("ATCODER");assertEquals(1,manager.list(query).get("total"));
        jdbc.update("UPDATE problem SET is_group=TRUE WHERE id=1");assertEquals(0,manager.list(query).get("total"));
        query.setSource("ALL");assertEquals(1,manager.list(query).get("total"));jdbc.update("DELETE FROM problem_tag WHERE pid=2");
        result=manager.list(query);assertEquals(0,result.get("total"));assertEquals(2L,totals(result).get("solved"));
        query.setGroupCode("math");assertEquals(0,manager.list(query).get("total"),"Cached AT/NC tags must not restore revoked public classification");
        query.setGroupCode("");assertEquals(2,manager.list(query).get("total"));
    }
    @Test void rechecksAccountStatusBeforeReturningResults() {
        doNothing().doThrow(new ObjectiveException(403,"账号不可用")).when(access).requireActiveUser("alice");assertEquals(403,assertThrows(ObjectiveException.class,this::list).getStatus());
    }
}
