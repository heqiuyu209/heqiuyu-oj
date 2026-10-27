package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.external.ProblemIdentityResolver;
import top.hcode.hoj.manager.oj.external.ExternalAccountManager;
import top.hcode.hoj.manager.oj.external.NowCoderContextManager;
import top.hcode.hoj.manager.oj.external.NowCoderPracticeManager;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningEvidenceManagerTest {
    JdbcTemplate jdbc;
    ObjectMapper json = new ObjectMapper();
    AlgorithmCatalog catalog = new AlgorithmCatalog();
    LearningProfileManager local;
    LearningEvidenceManager manager;
    long clock = 1800000000000L;

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:source_ability;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(100),is_remote BOOLEAN,auth INT,is_group BOOLEAN,gid BIGINT)");
        jdbc.execute("CREATE TABLE tag(id BIGINT PRIMARY KEY,name VARCHAR(100),gid BIGINT)");
        jdbc.execute("CREATE TABLE problem_tag(pid BIGINT,tid BIGINT)");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)"); jdbc.update("INSERT INTO user_info VALUES('alice',0)");
        for (String file : Arrays.asList("external-learning.sql", "remote-sources.sql", "atcoder-learning.sql", "nowcoder-learning.sql", "nowcoder-sources.sql")) {
            String sql = StreamUtils.copyToString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci", "").replace("DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND)", "CURRENT_TIMESTAMP(3)");
            try (Connection c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        }
        local = mock(LearningProfileManager.class);
        when(local.profile("alice")).thenReturn(localProfile());
        when(local.publicProgrammingEvidence("alice")).thenReturn(Collections.emptyList());
        when(local.publicProgrammingGroups("alice")).thenReturn(Collections.emptyMap());
        manager = new LearningEvidenceManager() { @Override protected long now() { return clock; } };
        ReflectionTestUtils.setField(manager, "jdbc", jdbc); ReflectionTestUtils.setField(manager, "profiles", local);
        ReflectionTestUtils.setField(manager, "catalog", catalog); ReflectionTestUtils.setField(manager, "json", json);
    }
    Map<String, Object> metrics(long attempted, long solved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempted", attempted); m.put("solved", solved); m.put("objectiveAttempted", 0L); m.put("objectiveSolved", 0L);
        m.put("ratedSolved", 0); m.put("representativeRating", null); m.put("unratedProgrammingSolved", solved); m.put("unmappedSolved", 0L);
        return m;
    }
    Map<String, Object> localProfile() {
        Map<String, Object> p = new LinkedHashMap<>(); p.put("uid", "alice"); p.put("overall", metrics(0, 0));
        List<Map<String, Object>> axes = new ArrayList<>(); catalog.groups().forEach((code, name) -> { Map<String, Object> a = metrics(0, 0); a.put("code", code); a.put("name", name); axes.add(a); });
        p.put("axes", axes); return p;
    }
    void account(long id, String platform, boolean verified) {
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at,proof_code) VALUES(?,?,?,?,?,?,1,?,?,?,?)", id, "alice", platform, "account" + id, "account" + id, verified ? "account" + id : null, new Timestamp(clock), new Timestamp(clock), new Timestamp(clock), "DO_NOT_EXPOSE_PROOF");
        jdbc.update("INSERT INTO external_sync_job(account_id,scan_token,stage) VALUES(?,'fixture','DONE')", id);
        if ("NOWCODER".equals(platform)) jdbc.update("INSERT INTO external_nowcoder_profile(account_id) VALUES(?)", id);
    }
    void submission(long account, long id, String platform, String key, Integer rating, String tags, boolean solved, boolean individual) {
        jdbc.update("INSERT IGNORE INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,'Same title',?,?,?)", key, platform, rating, tags, new Timestamp(clock));
        jdbc.update("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(?,?,?,?,100,?,'fixture')", account, id, key, solved ? "OK" : "WRONG_ANSWER", individual);
    }
    void local(long id, String display, String group) {
        Map<String, Object> row = new HashMap<>(); row.put("id", id); row.put("problem_id", display); row.put("is_remote", true); row.put("solved", 1);
        when(local.publicProgrammingEvidence("alice")).thenReturn(Collections.singletonList(row));
        when(local.publicProgrammingGroups("alice")).thenReturn(Collections.singletonMap(id, Collections.singleton(group)));
        Map<String, Object> p = localProfile(); p.put("overall", metrics(1, 1)); axis(p, group).put("solved", 1L); axis(p, group).put("attempted", 1L); when(local.profile("alice")).thenReturn(p);
    }
    @Test void numericCfPublicationMappingReconcilesAbilityCountsWithoutGuessingTheDisplayId() {
        account(1,"CODEFORCES",true);submission(1,1,"CODEFORCES","cf:contest:921:01",3200,"[\"dp\"]",true,true);
        local(7,"CF-92101","dp");
        jdbc.update("INSERT INTO problem(id,problem_id,is_remote) VALUES(7,'CF-92101',TRUE)");
        jdbc.update("INSERT INTO remote_problem_import(problem_key,requested_by,requested_author,last_requested_at,problem_id,state) VALUES('cf:contest:921:01','alice','alice',?,7,'FAILED')",new Timestamp(clock));
        Map<String,Object> result=manager.profile("alice");
        assertEquals(1L,overall(total(result)).get("solved"));assertEquals(1L,overall(total(result)).get("duplicatesRemoved"));
        jdbc.update("DELETE FROM remote_problem_import WHERE problem_id=7");
        assertEquals(2L,overall(total(manager.profile("alice"))).get("solved"));
    }
    @SuppressWarnings("unchecked") Map<String, Object> total(Map<String, Object> p) { return (Map<String, Object>) p.get("combined"); }
    @SuppressWarnings("unchecked") Map<String, Object> overall(Map<String, Object> p) { return (Map<String, Object>) p.get("overall"); }
    @SuppressWarnings("unchecked") Map<String, Object> axis(Map<String, Object> p, String code) { return ((List<Map<String, Object>>) p.get("axes")).stream().filter(a -> code.equals(a.get("code"))).findFirst().get(); }
    @SuppressWarnings("unchecked") Map<String, Object> source(Map<String, Object> p, String code) { return ((List<Map<String, Object>>) p.get("sources")).stream().filter(a -> code.equals(a.get("source"))).findFirst().get(); }

    void donor(long id,String display) {
        jdbc.update("INSERT INTO problem VALUES(?,?,TRUE,1,FALSE,NULL)",id,display);
        jdbc.update("INSERT INTO tag VALUES(?,'dp',NULL)",id);jdbc.update("INSERT INTO problem_tag VALUES(?,?)",id,id);
    }
    @Test void externalOnlyAtcoderEvidenceUsesKnownPublicClassificationWithoutCreatingLocalAttempts() {
        account(1,"ATCODER",true);submission(1,1,"ATCODER","atcoder:task:abc400_a",9999,"[\"graphs\"]",true,false);
        String h=String.join("",Collections.nCopies(64,"a"));
        jdbc.update("INSERT INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,content_hash,seen_token,last_seen_at,changed_at,estimated_difficulty) VALUES('atcoder:task:abc400_A','abc400_A','abc400','A','Known task',?,'fixture',?,?,900)",h,new Timestamp(clock),new Timestamp(clock));
        donor(1,"AC-ABC400_A");
        Map<String,Object> p=manager.profile("alice"), at=source(p,"ATCODER");
        assertEquals(1L,axis(at,"dp").get("solved"));assertEquals(1L,axis(total(p),"dp").get("solved"));
        assertEquals(0L,overall(source(p,"HOJ")).get("attempted"));assertEquals(1L,overall(total(p)).get("solved"));
        assertEquals(0L,overall(total(p)).get("duplicatesRemoved"));assertEquals(900,overall(at).get("representativeRating"));
        assertEquals(0L,axis(at,"graph").get("solved"));assertEquals(0L,overall(at).get("unmappedSolved"));
        jdbc.update("UPDATE problem SET auth=2 WHERE id=1");
        p=manager.profile("alice");at=source(p,"ATCODER");
        assertEquals(1L,overall(at).get("solved"));assertEquals(1L,overall(at).get("unmappedSolved"));assertEquals(false,at.get("coverageAvailable"));
        assertEquals(0L,axis(total(p),"dp").get("solved"));assertEquals(0L,axis(at,"graph").get("solved"));
    }
    @Test void externalOnlyNowcoderAliasesKeepCountsWhileClassificationFollowsCurrentConfirmedIdentity() {
        account(1,"NOWCODER",true);
        submission(1,1,"NOWCODER","nowcoder:contest:77:A",9999,"[\"dp\"]",true,false);
        submission(1,2,"NOWCODER","nowcoder:acm:12",9999,"[\"dp\"]",true,false);
        String h=String.join("",Collections.nCopies(64,"a"));
        jdbc.update("INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,question_id,title,source_rating,content_hash,last_seen_at,changed_at) VALUES('nowcoder:contest:77:A',12,'8','Alias',NULL,?,?,?),('nowcoder:acm:12',12,NULL,'Native',1100,?,?,?)",h,new Timestamp(clock),new Timestamp(clock),h,new Timestamp(clock),new Timestamp(clock));
        donor(1,"NC-77-A");
        Map<String,Object> p=manager.profile("alice"), nc=source(p,"NOWCODER");
        assertEquals(1L,overall(nc).get("solved"));assertEquals(1L,axis(nc,"dp").get("solved"));
        assertEquals(1L,overall(total(p)).get("duplicatesRemoved"));assertEquals(1100,overall(nc).get("representativeRating"));
        assertEquals(0L,overall(source(p,"HOJ")).get("attempted"));
        jdbc.update("UPDATE remote_nowcoder_problem SET question_id=NULL WHERE problem_key='nowcoder:contest:77:A'");
        p=manager.profile("alice");nc=source(p,"NOWCODER");
        assertEquals(2L,overall(nc).get("solved"));assertEquals(1L,axis(nc,"dp").get("solved"));assertEquals(1L,overall(nc).get("unmappedSolved"));
        jdbc.update("UPDATE problem SET auth=2 WHERE id=1");
        nc=source(manager.profile("alice"),"NOWCODER");assertEquals(2L,overall(nc).get("unmappedSolved"));assertEquals(false,nc.get("coverageAvailable"));
    }

    @Test void combinesExplicitTasksWhileKeepingLocalAndCfDifficultySeparate() {
        local(1, "CF-4A", "math");
        overall(local.profile("alice")).put("representativeRating", 2100); overall(local.profile("alice")).put("ratedSolved", 1);
        account(1, "CODEFORCES", true);
        submission(1, 1, "CODEFORCES", "cf:contest:4:A", 9999, "[\"math\",\"number theory\"]", true, true);
        submission(1, 2, "CODEFORCES", "cf:contest:5:A", 1200, "[\"dp\"]", true, true);
        submission(1, 3, "CODEFORCES", "cf:contest:6:A", 3000, "[\"graphs\"]", true, false);
        jdbc.update("INSERT INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,rating,tags_json,content_hash,seen_token,last_seen_at,changed_at) VALUES('cf:contest:4:A',4,'A','A',800,'[\"math\"]',?,'fixture',?,?)", String.join("", Collections.nCopies(64, "a")), new Timestamp(clock), new Timestamp(clock));
        Map<String, Object> p = manager.profile("alice"), combined = total(p), cf = source(p, "CODEFORCES");
        assertEquals(2L, overall(combined).get("solved")); assertEquals(1L, overall(combined).get("duplicatesRemoved"));
        assertNull(overall(combined).get("representativeRating")); assertNull(overall(combined).get("ratedSolved"));
        assertEquals(1L, axis(combined, "math").get("solved")); assertEquals(1L, axis(combined, "dp").get("solved"));
        assertEquals(0L, axis(combined, "graph").get("solved"));
        assertEquals(800, axis(cf, "math").get("representativeRating")); assertEquals(1200, overall(cf).get("representativeRating"));
        assertEquals(2100, overall(source(p, "HOJ")).get("representativeRating"));
        assertFalse(local.profile("alice").containsKey("source"));
    }
    @Test void refreshStatesKeepCachedEvidenceAndFollowVerifiedSourceJobsIndependently() {
        account(1, "CODEFORCES", true); account(2, "CODEFORCES", true);
        account(3, "ATCODER", true); account(4, "NOWCODER", true); account(5, "NOWCODER", false);
        submission(1, 1, "CODEFORCES", "cf:contest:4:A", 800, "[\"math\"]", true, true);
        jdbc.update("UPDATE external_sync_job SET stage='STATUS',lease_until=? WHERE account_id=5", new Timestamp(clock + 90000));
        for (String platform : Arrays.asList("CODEFORCES", "ATCODER", "NOWCODER")) {
            Map<String, Object> initial = source(manager.profile("alice"), platform);
            assertEquals("IDLE", initial.get("syncState")); assertEquals(false, initial.get("stale"));
        }
        jdbc.update("UPDATE external_sync_job SET stage='STATUS' WHERE account_id=1");
        Map<String, Object> queued = source(manager.profile("alice"), "CODEFORCES");
        assertEquals("QUEUED", queued.get("syncState")); assertEquals(true, queued.get("stale"));
        assertEquals("QUEUED", total(manager.profile("alice")).get("syncState"));
        assertEquals(true, total(manager.profile("alice")).get("stale"));
        assertEquals(true, queued.get("historyComplete")); assertEquals(1L, overall(queued).get("solved"));
        jdbc.update("UPDATE external_sync_job SET lease_until=? WHERE account_id=1", new Timestamp(clock + 90000));
        assertEquals("RUNNING", source(manager.profile("alice"), "CODEFORCES").get("syncState"));
        jdbc.update("UPDATE external_sync_job SET lease_until=?,failures=1 WHERE account_id=1", new Timestamp(clock - 1));
        Map<String, Object> backoff = source(manager.profile("alice"), "CODEFORCES");
        assertEquals("BACKOFF", backoff.get("syncState")); assertEquals(true, backoff.get("stale"));
        assertEquals(1L, overall(backoff).get("solved"));
        jdbc.update("UPDATE external_sync_job SET stage='DONE',lease_until=NULL,failures=0 WHERE account_id=1");
        jdbc.update("UPDATE external_sync_job SET stage='STATUS' WHERE account_id=3");
        jdbc.update("UPDATE external_sync_job SET stage='VERIFY' WHERE account_id=4");
        Map<String, Object> latest = manager.profile("alice");
        assertEquals("IDLE", source(latest, "CODEFORCES").get("syncState"));
        assertEquals(false, source(latest, "CODEFORCES").get("stale"));
        for (String platform : Arrays.asList("ATCODER", "NOWCODER")) {
            assertEquals("QUEUED", source(latest, platform).get("syncState"));
            assertEquals(true, source(latest, platform).get("stale"));
            assertEquals(true, source(latest, platform).get("historyComplete"));
        }
    }
    @Test void excludesUnverifiedEvidenceAndShowsUnknownTaxonomyInsteadOfInventedZeroAbility() throws Exception {
        account(1, "CODEFORCES", false); submission(1, 1, "CODEFORCES", "cf:contest:4:A", 9000, "[\"dp\"]", true, true);
        account(2, "ATCODER", true); submission(2, 1, "ATCODER", "atcoder:task:abc100_a", 800, "[]", true, false);
        account(3, "NOWCODER", true); submission(3, 1, "NOWCODER", "nowcoder:acm:6", null, "[]", true, false);
        Map<String, Object> p = manager.profile("alice");
        assertEquals(2L, overall(total(p)).get("solved")); assertEquals(2L, overall(total(p)).get("unmappedSolved"));
        assertEquals(false, total(p).get("coverageAvailable"));
        assertEquals(0, source(p, "CODEFORCES").get("verifiedAccounts")); assertEquals(0L, overall(source(p, "CODEFORCES")).get("solved"));
        assertEquals(false, source(p, "ATCODER").get("coverageAvailable")); assertEquals(false, source(p, "NOWCODER").get("coverageAvailable"));
        assertEquals("ACCOUNT_ATTRIBUTED", source(p, "ATCODER").get("participationScope")); assertNull(overall(source(p, "NOWCODER")).get("representativeRating"));
        assertFalse(json.writeValueAsString(p).contains("DO_NOT_EXPOSE_PROOF")); assertFalse(json.writeValueAsString(p).contains("account2"));
    }
    @Test void officialNowcoderAliasDeduplicatesStatisticsWithoutChangingStoredSubmissionContexts() {
        local(1, "NC-11244-A", "math"); account(1, "NOWCODER", true);
        submission(1, 1, "NOWCODER", "nowcoder:contest:11244:A", null, "[]", true, false);
        submission(1, 2, "NOWCODER", "nowcoder:acm:218144", null, "[]", true, false);
        String h = String.join("", Collections.nCopies(64, "a"));
        jdbc.update("INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,question_id,title,content_hash,last_seen_at,changed_at) VALUES('nowcoder:contest:11244:A',218144,'99','Same title',?,?,?),('nowcoder:acm:218144',218144,'99','Same title',?,?,?)", h, new Timestamp(clock), new Timestamp(clock), h, new Timestamp(clock), new Timestamp(clock));
        jdbc.update("UPDATE remote_nowcoder_problem SET source_rating=1100 WHERE problem_key='nowcoder:acm:218144'");
        Map<String, Object> p = manager.profile("alice");
        assertEquals(1L, overall(total(p)).get("solved")); assertEquals(1L, overall(source(p, "NOWCODER")).get("solved"));
        assertEquals(2L, overall(total(p)).get("duplicatesRemoved"));
        assertEquals(1100, overall(source(p, "NOWCODER")).get("representativeRating")); assertEquals(1L, axis(total(p), "math").get("solved"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE problem_key='nowcoder:contest:11244:A'", Integer.class));
        ExternalAccountManager existing = new ExternalAccountManager();
        ReflectionTestUtils.setField(existing, "jdbc", jdbc); ReflectionTestUtils.setField(existing, "profiles", local);
        ReflectionTestUtils.setField(existing, "catalog", catalog); ReflectionTestUtils.setField(existing, "json", json);
        ReflectionTestUtils.setField(existing, "transactions", new DataSourceTransactionManager(jdbc.getDataSource()));
        NowCoderContextManager contexts=mock(NowCoderContextManager.class);when(contexts.view(anyLong(),anyBoolean())).thenReturn(Collections.singletonMap("available",false));ReflectionTestUtils.setField(existing,"nowcoderContexts",contexts);
        NowCoderPracticeManager practice=mock(NowCoderPracticeManager.class);when(practice.view(anyLong(),anyBoolean())).thenReturn(Collections.singletonMap("available",false));ReflectionTestUtils.setField(existing,"nowcoderPractice",practice);
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) existing.publicView("alice").get("summary");
        assertEquals(1, summary.get("uniqueSolved")); assertEquals(1, summary.get("externalSolved")); assertEquals(2L, summary.get("duplicatesRemoved"));
        jdbc.update("UPDATE remote_nowcoder_problem SET question_id=NULL WHERE problem_key='nowcoder:contest:11244:A'");
        assertEquals(2L, overall(total(manager.profile("alice"))).get("solved"));
    }
    @Test void atcoderUsesKnownCaseAliasesAndCurrentDifficultyModelWithoutMixingScales() {
        local(1, "AC-tenka1_quala_4", "dp"); account(1, "ATCODER", true);
        submission(1, 1, "ATCODER", "atcoder:task:tenka1_qualA_4", 999, "[]", true, false);
        jdbc.update("INSERT INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,estimated_difficulty,content_hash,seen_token,last_seen_at,changed_at) VALUES('atcoder:task:tenka1_qualA_4','tenka1_qualA_4','tenka1','D','Same title',1300,?,'fixture',?,?)", String.join("", Collections.nCopies(64, "a")), new Timestamp(clock), new Timestamp(clock));
        Map<String, Object> p = manager.profile("alice");
        assertEquals(1L, overall(total(p)).get("solved")); assertEquals(1300, overall(source(p, "ATCODER")).get("representativeRating"));
        assertEquals("ATCODER_PROBLEMS_ESTIMATE", source(p, "ATCODER").get("difficultyMetric"));
        jdbc.update("DELETE FROM external_atcoder_problem");
        assertEquals(2L, overall(total(manager.profile("alice"))).get("solved"));
    }
    @Test void repeatedAccountsRejudgesAndRevokedVerificationChangeEvidenceWithoutDoubleCounting() {
        account(1, "CODEFORCES", true); account(2, "CODEFORCES", true);
        submission(1, 1, "CODEFORCES", "cf:contest:4:A", 800, "[\"math\"]", false, true);
        submission(2, 1, "CODEFORCES", "cf:contest:4:A", 800, "[\"math\"]", true, true);
        Map<String, Object> p = manager.profile("alice"); assertEquals(1L, overall(total(p)).get("solved")); assertEquals(1L, overall(source(p, "CODEFORCES")).get("attempted"));
        jdbc.update("UPDATE external_submission SET verdict='OK'");
        assertEquals(1L, overall(total(manager.profile("alice"))).get("duplicatesRemoved"));
        jdbc.update("UPDATE external_submission SET verdict='WRONG_ANSWER'");
        assertEquals(0L, overall(total(manager.profile("alice"))).get("solved"));
        jdbc.update("UPDATE external_account SET verified_identity=NULL");
        assertEquals(0L, overall(total(manager.profile("alice"))).get("attempted"));
    }
    @Test void objectiveMetricsComeOnlyFromAlreadyReleasedLocalEvidenceAndPartialCachesAreMarked() {
        Map<String, Object> localProfile = local.profile("alice"); overall(localProfile).put("objectiveAttempted", 2L); overall(localProfile).put("objectiveSolved", 1L);
        axis(localProfile, "dp").put("objectiveAttempted", 2L); axis(localProfile, "dp").put("objectiveSolved", 1L);
        account(1, "CODEFORCES", true); submission(1, 1, "CODEFORCES", "cf:contest:4:A", 800, "[\"math\"]", true, true);
        jdbc.update("UPDATE external_account SET history_complete=0,last_success_at=NULL");
        Map<String, Object> p = manager.profile("alice"); assertEquals(1L, overall(total(p)).get("objectiveSolved")); assertEquals(2L, axis(total(p), "dp").get("objectiveAttempted"));
        assertEquals(false, source(p, "CODEFORCES").get("historyComplete")); assertEquals(true, source(p, "CODEFORCES").get("stale"));
        assertEquals(0L, overall(source(p, "CODEFORCES")).get("objectiveAttempted"));
        jdbc.update("UPDATE external_account SET history_complete=1,last_success_at=?", new Timestamp(clock)); jdbc.update("UPDATE external_sync_job SET failures=1");
        p = manager.profile("alice"); assertEquals(true, source(p, "CODEFORCES").get("historyComplete")); assertEquals(true, source(p, "CODEFORCES").get("stale"));
        when(local.profile("disabled")).thenThrow(new ObjectiveException(404, "用户不存在"));
        assertThrows(ObjectiveException.class, () -> manager.profile("disabled"));
    }
    @Test void titlesAndUnknownAliasesNeverMergeUnrelatedProblems() {
        account(1, "NOWCODER", true);
        submission(1, 1, "NOWCODER", "nowcoder:acm:6", null, "[]", true, false);
        submission(1, 2, "NOWCODER", "nowcoder:contest:7:A", null, "[]", true, false);
        assertEquals(2L, overall(total(manager.profile("alice"))).get("solved"));
        Map<String, String> resolved = new ProblemIdentityResolver(jdbc).resolve(Arrays.asList("atcoder:task:unknown_A", "atcoder:task:unknown_a", "nowcoder:contest:7:A"));
        assertEquals("atcoder:task:unknown_A", resolved.get("atcoder:task:unknown_A")); assertEquals("nowcoder:contest:7:A", resolved.get("nowcoder:contest:7:A"));
    }
}
