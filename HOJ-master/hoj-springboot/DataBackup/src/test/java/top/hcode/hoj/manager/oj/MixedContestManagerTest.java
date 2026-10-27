package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.dao.contest.*;
import top.hcode.hoj.pojo.dto.*;
import top.hcode.hoj.pojo.entity.contest.*;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.*;
import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual SQL, row locks and deadline/release rules; no external services or user data. */
class MixedContestManagerTest {
    long time; String uid; boolean root; boolean registered;
    JdbcTemplate jdbc; TransactionTemplate tx; ObjectMapper json;
    PaperManager papers; MixedContestManager mixed; MixedContestRankManager ranks;
    PaperProgramManager programs; ObjectiveManager objectives; ContestRegisterEntityService registrations;
    Contest contest; PaperDTO.Paper paper; ObjectiveDTO.Question question;
    Map<Long, PaperProgramSnapshotDTO> snapshots; long sequence;
    @BeforeEach void setup() throws Exception {
        time = 100000; uid = "alice"; root = false; registered = false; sequence = 0; snapshots = new HashMap<>(); json = new ObjectMapper();
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:mixed_contest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS"); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        try (Connection c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new ClassPathResource("paper.sql")); }
        jdbc.execute("CREATE TABLE contest(id BIGINT PRIMARY KEY)"); jdbc.update("INSERT INTO contest VALUES(1)");
        String virtualSchema = org.springframework.util.StreamUtils.copyToString(new ClassPathResource("virtual-contests.sql").getInputStream(), java.nio.charset.StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci", "");
        try (Connection c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new org.springframework.core.io.ByteArrayResource(virtualSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        jdbc.execute("CREATE TABLE contest_paper(contest_id BIGINT PRIMARY KEY,paper_id BIGINT,rules_json TEXT,template_json CLOB)");
        // H2's CLOB differs from MySQL LONGTEXT's String mapping.
        jdbc.execute("ALTER TABLE contest_paper ALTER COLUMN template_json LONGTEXT");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32),username VARCHAR(32),nickname VARCHAR(32),realname VARCHAR(32))");
        jdbc.execute("CREATE TABLE role(id BIGINT,role VARCHAR(32))"); jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id BIGINT)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT AUTO_INCREMENT PRIMARY KEY,paper_run_id BIGINT,paper_snapshot_id BIGINT,submit_time TIMESTAMP,status INT,score INT)");
        jdbc.update("INSERT INTO user_info VALUES('alice','alice',NULL,NULL),('bob','bob',NULL,NULL),('charlie','charlie',NULL,NULL)");
        programs = mock(PaperProgramManager.class); objectives = mock(ObjectiveManager.class);
        question = new ObjectiveDTO.Question(); question.setId(1L); question.setVersion(1); question.setQuestionType("SINGLE_CHOICE"); question.setTitle("冻结的选择题"); question.setCorrectAnswers(Collections.singletonList("A")); question.setOptions(Collections.emptyList());
        when(objectives.collectionQuestionSnapshot(1L)).thenAnswer(a -> json.readValue(json.writeValueAsString(question), ObjectiveDTO.Question.class));
        when(objectives.normalize(any(), any(), anyBoolean())).thenAnswer(a -> a.getArgument(1));
        when(objectives.score(any(), any())).thenAnswer(a -> ((List<?>) a.getArgument(1)).contains("A") ? 1 : 0);
        when(objectives.correctAnswers(any())).thenReturn(Collections.singletonList("A"));
        PaperProgramSnapshotDTO program = new PaperProgramSnapshotDTO(); program.setProblem(new Problem().setId(1L).setTitle("冻结的编程题").setType(0)); program.setLanguages(Collections.singletonList("C++"));
        when(programs.captureForCollection(1L)).thenReturn(program);
        when(programs.copy(anyLong(), anyString(), anyString(), any())).thenAnswer(a -> { long id = ++sequence; snapshots.put(id, a.getArgument(3)); return id; });
        when(programs.freezeForCollection(anyLong(), anyString(), anyString(), anyLong())).thenAnswer(a -> { long id = ++sequence; snapshots.put(id, program); return id; });
        when(programs.snapshot(anyLong())).thenAnswer(a -> snapshots.get(a.getArgument(0)));
        when(programs.submissions(anyLong(), anyLong(), anyString())).thenReturn(Collections.emptyList());
        ContestEntityService contests = mock(ContestEntityService.class);
        contest = new Contest().setId(1L).setUid("teacher").setType(0).setTitle("混合赛").setStartTime(new Date(200000)).setEndTime(new Date(400000)).setVisible(true).setIsGroup(false).setAuth(0).setStatus(-1).setOpenAccountLimit(false).setSealRank(false);
        when(contests.getById(1L)).thenReturn(contest);
        registrations = mock(ContestRegisterEntityService.class);
        when(registrations.getOne(any(), anyBoolean())).thenAnswer(a -> registered ? new ContestRegister() : null);
        when(registrations.getOne(any())).thenAnswer(a -> registered ? new ContestRegister() : null);
        ContestValidator validator = new ContestValidator(); inject(validator, "contestRegisterEntityService", registrations); inject(validator, "groupValidator", mock(GroupValidator.class));
        mixed = new MixedContestManager() {
            @Override protected long now() { return time; }
            @Override protected boolean root() { return root; }
            @Override protected AccountProfile actor() { AccountProfile p = new AccountProfile(); p.setUid(uid); p.setUsername(uid); return p; }
        };
        inject(mixed, "jdbc", jdbc); inject(mixed, "json", json); inject(mixed, "contests", contests); inject(mixed, "validator", validator); inject(mixed, "objectives", objectives); inject(mixed, "programs", programs);
        papers = new PaperManager() { @Override protected String uid() { return uid; } @Override protected long now() { return time; } @Override protected boolean manageAll() { return true; } };
        inject(papers, "jdbc", jdbc); inject(papers, "json", json); inject(papers, "objectives", objectives); inject(papers, "programs", programs); inject(papers, "mixed", mixed);
        ranks = new MixedContestRankManager() { @Override protected long now() { return time; } };
        inject(ranks, "mixed", mixed); inject(ranks, "jdbc", jdbc); inject(ranks, "json", json); inject(ranks, "objectives", objectives); inject(ranks, "validator", validator);
        paper = new PaperDTO.Paper(); paper.setId(1L); paper.setVersion(1); paper.setTitle("日常卷"); paper.setProgramMode("AFTER_FINISH"); paper.setScoreRule("BEST"); paper.setAnswerPolicy("AFTER_FINISH"); paper.setExplanationPolicy("AFTER_FINISH");
        PaperDTO.Item objective = new PaperDTO.Item(); objective.setKind("OBJECTIVE"); objective.setReferenceId(1L); objective.setScore(10);
        PaperDTO.Item programming = new PaperDTO.Item(); programming.setKind("PROGRAMMING"); programming.setReferenceId(1L); programming.setScore(20); paper.setItems(Arrays.asList(objective, programming));
        jdbc.update("INSERT INTO paper(id,title,content_json,version,published,created_by,gmt_create,gmt_modified) VALUES(1,?,?,1,1,'teacher',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", paper.getTitle(), json.writeValueAsString(paper));
        root = true; MixedContestDTO.Config config = new MixedContestDTO.Config(); config.setPaperId(1L); transaction(() -> { mixed.configure(null, contest, config); return null; }); root = false;
    }
    void inject(Object target, String field, Object value) { ReflectionTestUtils.setField(target, field, value); }
    <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    long start() { return ((Number) transaction(() -> papers.startContest(1L)).get("id")).longValue(); }
    PaperDTO.Answer answer(String selected) { PaperDTO.Answer a = new PaperDTO.Answer(); a.setKey("OBJECTIVE:1"); a.setSelectedAnswers(Collections.singletonList(selected)); return a; }
    Map<String, Object> item(Map<String, Object> run) { return ((List<Map<String, Object>>) run.get("items")).get(0); }
    @Test void actualClockSingleAttemptAndSeparatePracticeAreEnforced() {
        root = true; assertThrows(ObjectiveException.class, this::start); root = false;
        time = 200000; long id = start(); assertEquals(id, start()); assertEquals("LIVE", papers.run(id).get("programMode"));
        assertEquals(0L, papers.histories(1).get("total"));
        PaperDTO.Start dto = new PaperDTO.Start(); dto.setPaperId(1L); dto.setRequestId("practice_001"); long practice = ((Number) transaction(() -> papers.start(dto)).get("id")).longValue(); assertNotEquals(id, practice);
        assertEquals(1L, papers.histories(1).get("total"));
    }
    @Test void deadlineLocksEveryWriteAndFinishesWithoutABrowserOrPrincipal() {
        time = 200000; long id = start(); transaction(() -> papers.answer(id, answer("A")));
        time = 400000; assertThrows(ObjectiveException.class, () -> transaction(() -> papers.answer(id, answer("B"))));
        assertThrows(ObjectiveException.class, () -> transaction(() -> papers.submit(id, answer("A"))));
        uid = null; transaction(() -> { papers.finishExpired(id); papers.finishExpired(id); return null; }); uid = "alice";
        Map<String, Object> result = papers.run(id); assertEquals("FINISHED", result.get("state")); assertEquals(10.0, result.get("score")); assertEquals(Collections.singletonList("A"), item(result).get("selectedAnswers"));
        uid = "bob"; assertThrows(ObjectiveException.class, this::start);
    }
    @Test void snapshotsAreCommonForEntrantsAndIndependentOfBankAndPracticeVisibility() throws Exception {
        time = 200000; long first = start(); question.setTitle("后来修改的题目");
        jdbc.update("UPDATE paper SET published=0,content_json=? WHERE id=1", json.writeValueAsString(paper));
        uid = "bob"; long second = start(); assertEquals("冻结的选择题", item(papers.run(second)).get("title"));
        uid = "alice"; assertEquals("冻结的选择题", item(papers.run(first)).get("title")); verify(programs, times(1)).captureForCollection(1L);
    }
    @Test void practiceApisCannotBypassOwnershipRegistrationDeadlineOrContestRelease() {
        time = 200000; contest.setAuth(2); assertThrows(ObjectiveException.class, this::start); registered = true;
        long id = start(); transaction(() -> papers.answer(id, answer("A"))); transaction(() -> papers.finish(id));
        assertEquals(10.0, item(papers.run(id)).get("score")); assertFalse(item(papers.run(id)).containsKey("correctAnswers"));
        time = 400000; assertTrue(item(papers.run(id)).containsKey("correctAnswers"));
        uid = "bob"; assertThrows(ObjectiveException.class, () -> papers.run(id)); assertThrows(ObjectiveException.class, () -> transaction(() -> papers.finish(id)));
    }
    @Test void contestReleaseRulesCanChangeButFormatAndScoringCannotChangeAfterStart() {
        time = 200000; long id = start(); transaction(() -> papers.finish(id)); MixedContestDTO.Config config = mixed.config(1L); config.setAnswerPolicy("NEVER");
        transaction(() -> { mixed.configure(contest, contest, config); return null; }); time = 400000;
        assertFalse(item(papers.run(id)).containsKey("correctAnswers"));
        config.setPaperId(2L); assertThrows(ObjectiveException.class, () -> transaction(() -> { mixed.configure(contest, contest, config); return null; }));
        assertThrows(ObjectiveException.class, () -> transaction(() -> { mixed.configure(contest, contest, null); return null; }));
    }
    void judged(long run, long at, int status) {
        long snapshot = snapshots.keySet().stream().max(Long::compare).get();
        jdbc.update("INSERT INTO judge(paper_run_id,paper_snapshot_id,submit_time,status,score) VALUES(?,?,?,?,0)", run, snapshot, new java.sql.Timestamp(at), status);
    }
    List<Map<String, Object>> rows() { return (List<Map<String, Object>>) ranks.rank(1L).get("records"); }
    @Test void acmRanksObjectiveFirstThenAcAndSubmissionTimePenaltyExcludingCompileErrors() {
        time = 200000; long a = start(); transaction(() -> papers.answer(a, answer("A"))); judged(a, 210000, -2); judged(a, 220000, -1); judged(a, 260000, 0); transaction(() -> papers.finish(a));
        uid = "bob"; long b = start(); judged(b, 230000, 0); transaction(() -> papers.finish(b));
        uid = "charlie"; long c = start(); transaction(() -> papers.answer(c, answer("A"))); judged(c, 270000, 0); transaction(() -> papers.finish(c));
        time = 300000; List<Map<String, Object>> rows = rows(); assertEquals("charlie", rows.get(0).get("uid")); assertEquals("alice", rows.get(1).get("uid")); assertEquals("bob", rows.get(2).get("uid")); assertEquals(1260L, rows.get(1).get("penalty"));
        contest.setType(1); assertEquals("alice", rows().get(0).get("uid")); assertEquals(1, rows().get(1).get("rank"));
    }
    long virtualWindow() {
        time = Math.max(time, 500000);
        jdbc.update("INSERT INTO contest_virtual_run(uid,cid,request_id,title,started_at,ends_at) VALUES(?,1,?,?,?,?)", uid, UUID.randomUUID().toString(), "virtual", new java.sql.Timestamp(time), new java.sql.Timestamp(time + 200000));
        return jdbc.queryForObject("SELECT MAX(id) FROM contest_virtual_run", Long.class);
    }
    long virtualPaper(long window) { return ((Number) transaction(() -> papers.startVirtual(window)).get("id")).longValue(); }
    @Test void virtualAnswersAndSnapshotsRemainSeparateFromFormalAndPracticeRecords() {
        time=200000;long formal=start();transaction(()->papers.answer(formal,answer("B")));transaction(()->papers.finish(formal));
        long window=virtualWindow(),virtual=virtualPaper(window);assertNotEquals(formal,virtual);assertEquals(virtual,virtualPaper(window));
        assertNull(jdbc.queryForObject("SELECT contest_id FROM paper_run WHERE id=?",Long.class,virtual));assertEquals(window,jdbc.queryForObject("SELECT virtual_run_id FROM paper_run WHERE id=?",Long.class,virtual));
        transaction(()->papers.answer(virtual,answer("A")));Map<String,Object> result=transaction(()->papers.finish(virtual));assertEquals(true,result.get("virtual"));assertEquals(10.0,result.get("score"));assertFalse(item(result).containsKey("correctAnswers"));
        assertEquals(formal,start());assertEquals(Collections.singletonList("B"),item(papers.run(formal)).get("selectedAnswers"));assertEquals(1,rows().size());assertEquals(0.0,rows().get(0).get("objectiveScore"));assertEquals(0L,papers.histories(1).get("total"));
        assertEquals(2,snapshots.size());uid="bob";assertEquals(404,assertThrows(ObjectiveException.class,()->papers.run(virtual)).getStatus());
    }
    @Test void virtualDeadlineLocksAllWritesAndSystemClosureDoesNotNeedAPrincipal() {
        long window=virtualWindow(),id=virtualPaper(window);transaction(()->papers.answer(id,answer("A")));
        time=699999;assertEquals("IN_PROGRESS",papers.run(id).get("state"));time=700000;
        assertEquals(409,assertThrows(ObjectiveException.class,()->transaction(()->papers.answer(id,answer("B")))).getStatus());
        PaperDTO.Answer code=new PaperDTO.Answer();code.setKey("PROGRAMMING:1");code.setRequestId("virtual_submit");code.setCode("int main(){}");code.setLanguage("C++");assertThrows(ObjectiveException.class,()->transaction(()->papers.submit(id,code)));
        uid=null;transaction(()->{papers.finishExpired(id);return null;});assertEquals("FINISHED",jdbc.queryForObject("SELECT state FROM paper_run WHERE id=?",String.class,id));assertEquals(700000,jdbc.queryForObject("SELECT gmt_modified FROM paper_run WHERE id=?",java.sql.Timestamp.class,id).getTime());
        uid="alice";assertEquals(Collections.singletonList("A"),item(papers.run(id)).get("selectedAnswers"));verify(programs,never()).submitAs(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void virtualFeedbackUsesPersonalEndAndCurrentAdministrativeEmbargoWithoutChangingFrozenQuestions() {
        long id=virtualPaper(virtualWindow());transaction(()->papers.answer(id,answer("A")));transaction(()->papers.finish(id));
        question.setTitle("原题已修改");question.setCorrectAnswers(Collections.singletonList("B"));jdbc.update("UPDATE paper SET published=0 WHERE id=1");
        MixedContestDTO.Config config=mixed.config(1L);config.setAnswerPolicy("AT_TIME");config.setAnswerReleaseAt(900000L);transaction(()->{mixed.configure(contest,contest,config);return null;});
        time=700000;Map<String,Object> result=papers.run(id);assertFalse(item(result).containsKey("correctAnswers"));assertEquals(true,item(result).get("explanationVisible"));assertEquals(900000L,result.get("answerReleaseAt"));assertEquals("冻结的选择题",item(result).get("title"));
        time=900000;assertEquals(Collections.singletonList("A"),item(papers.run(id)).get("correctAnswers"));config.setAnswerPolicy("NEVER");transaction(()->{mixed.configure(contest,contest,config);return null;});assertFalse(item(papers.run(id)).containsKey("correctAnswers"));
    }
    @Test void virtualAccessReusesProtectedContestRegistrationOnStartAndEveryRead() {
        contest.setAuth(2);long window=virtualWindow();assertEquals(403,assertThrows(ObjectiveException.class,()->virtualPaper(window)).getStatus());registered=true;long id=virtualPaper(window);
        registered=false;assertEquals(403,assertThrows(ObjectiveException.class,()->papers.run(id)).getStatus());
    }
    @Test void oiVirtualResultsKeepTheirOwnFormatAndLastSubmissionScoreRule() {
        contest.setType(1);MixedContestDTO.Config config=mixed.config(1L);config.setScoreRule("LAST");root=true;transaction(()->{mixed.configure(contest,contest,config);return null;});root=false;
        long id=virtualPaper(virtualWindow());
        when(programs.submissions(eq(id),anyLong(),eq("alice"))).thenReturn(Arrays.asList(new Judge().setSubmitId(1L).setStatus(0).setScore(100),new Judge().setSubmitId(2L).setStatus(-1).setScore(0)));
        Map<String,Object> result=transaction(()->papers.finish(id));assertEquals(1,result.get("contestType"));assertEquals("LAST",result.get("scoreRule"));assertEquals(0.0,result.get("score"));assertFalse(item(result).containsKey("correctAnswers"));
    }
}
