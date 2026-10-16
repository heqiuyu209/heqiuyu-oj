package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.*;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real paper transactions/schema and objective grading, with an isolated asynchronous judge. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PaperManagerTest.Config.class)
class PaperManagerTest {
    static String uid = "alice";
    static boolean all = true;
    static long time = 1000000L;
    @Configuration
    @Import(ObjectiveManagerTest.Config.class)
    static class Config {
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor programMock() {
            return factory -> factory.registerSingleton("programs", mock(PaperProgramManager.class));
        }
        @Bean PaperManager papers() { return new PaperManager() {
            @Override protected String uid() { return uid; }
            @Override protected boolean manageAll() { return all; }
            @Override protected long now() { return time; }
        }; }
    }
    @Autowired PaperManager manager;
    @Autowired ObjectiveManager objectives;
    @Autowired PaperProgramManager programs;
    @Autowired DataSource ds;
    @Autowired ObjectMapper json;
    JdbcTemplate jdbc;
    Map<Long, PaperProgramSnapshotDTO> snapshots;
    Map<Long, List<Judge>> submissions;
    long nextId;
    @BeforeEach void resetDatabase() throws Exception {
        uid = "alice"; all = true; time = 1000000L; nextId = 0; reset(programs);
        snapshots = new HashMap<>(); submissions = new HashMap<>(); jdbc = new JdbcTemplate(ds);
        try (Connection connection = ds.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")); jdbc.execute("DROP ALL OBJECTS");
            for (String file : Arrays.asList("objective.sql", "paper.sql")) {
                String schema = StreamUtils.copyToString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
            }
        }
        when(programs.availableForCollection(anyLong())).thenAnswer(call -> new Problem().setId(call.getArgument(0)).setType(0).setIoScore(100));
        when(programs.freezeForCollection(anyLong(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            long id = ++nextId; PaperProgramSnapshotDTO snapshot = new PaperProgramSnapshotDTO();
            snapshot.setProblem(new Problem().setId(call.getArgument(3)).setTitle("冻结的 A+B").setDescription("输入两个整数").setSource("蓝桥杯练习").setType(0).setIoScore(100));
            snapshot.setLanguages(Arrays.asList("C++", "Python3")); snapshots.put(id, snapshot); submissions.put(id, new ArrayList<>()); return id;
        });
        when(programs.snapshot(anyLong())).thenAnswer(call -> snapshots.get(call.getArgument(0)));
        when(programs.submissions(anyLong(), anyLong(), anyString())).thenAnswer(call -> submissions.get(call.getArgument(1)));
        doAnswer(call -> { if (!Arrays.asList("C++", "Python3").contains(call.getArgument(1))) throw new ObjectiveException(400, "语言无效"); return null; }).when(programs).validate(anyLong(), any(), any());
        when(programs.submit(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            Judge judge = new Judge().setSubmitId(++nextId).setStatus(5); submissions.get(call.getArgument(1)).add(judge); return judge.getSubmitId();
        });
    }
    private ObjectiveDTO.Question blank() {
        ObjectiveDTO.Question q = new ObjectiveDTO.Question(); q.setTitle("填空题"); q.setStem("填写两个结果"); q.setQuestionType("FILL_BLANK"); q.setPublished(true); q.setExplanation("答案解析");
        ObjectiveDTO.Blank first = new ObjectiveDTO.Blank(); first.setId("a"); first.setScore(3); first.setAnswers(Arrays.asList("42", "0x2A")); first.setTrim(true); first.setCaseSensitive(false);
        ObjectiveDTO.Blank second = new ObjectiveDTO.Blank(); second.setId("b"); second.setScore(7); second.setAnswers(Collections.singletonList("OK")); second.setTrim(false); second.setCaseSensitive(true);
        q.setBlanks(Arrays.asList(first, second)); return objectives.saveQuestion(q);
    }
    private PaperDTO.Paper paper(String mode, ObjectiveDTO.Question q) {
        PaperDTO.Paper dto = new PaperDTO.Paper(); dto.setTitle("蓝桥杯混合练习"); dto.setProgramMode(mode); dto.setScoreRule("BEST"); dto.setPublished(true); dto.setAnswerPolicy("AFTER_FINISH"); dto.setExplanationPolicy("AFTER_FINISH");
        PaperDTO.Item a = new PaperDTO.Item(); a.setKind("OBJECTIVE"); a.setReferenceId(q.getId()); a.setScore(1);
        PaperDTO.Item b = new PaperDTO.Item(); b.setKind("PROGRAMMING"); b.setReferenceId(q.getId()); b.setScore(20);
        dto.setItems(Arrays.asList(a, b)); return manager.save(dto);
    }
    private Map<String, Object> start(Long id, String nonce) { PaperDTO.Start dto = new PaperDTO.Start(); dto.setPaperId(id); dto.setRequestId(nonce); return manager.start(dto); }
    private Long id(Map<String, Object> run) { return ((Number) run.get("id")).longValue(); }
    @SuppressWarnings("unchecked") private Map<String, Object> item(Map<String, Object> run, int i) { return ((List<Map<String, Object>>) run.get("items")).get(i); }
    private PaperDTO.Answer objective(Long q, String... answers) { PaperDTO.Answer dto = new PaperDTO.Answer(); dto.setKey("OBJECTIVE:" + q); dto.setSelectedAnswers(Arrays.asList(answers)); return dto; }
    private PaperDTO.Answer code(Long q, String nonce) { PaperDTO.Answer dto = new PaperDTO.Answer(); dto.setKey("PROGRAMMING:" + q); dto.setLanguage("C++"); dto.setCode("int main(){return 0;}"); dto.setRequestId(nonce); return dto; }
    private Judge judge() { return submissions.values().stream().flatMap(List::stream).findFirst().get(); }

    @Test void referencesShareBankIdsButNotPracticeRecordsAndDoNotLeakAnswers() throws Exception {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper paper = paper("AFTER_FINISH", q);
        Map<String, Object> run = start(paper.getId(), "start_0001");
        assertEquals(10, item(run, 0).get("maxScore")); assertEquals(30, run.get("maxScore"));
        String publicJson = json.writeValueAsString(run); assertFalse(publicJson.contains("0x2A")); assertFalse(publicJson.contains("correctAnswers")); assertFalse(publicJson.contains("答案解析"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM objective_run", Integer.class));
        ObjectiveDTO.StartRun practice = new ObjectiveDTO.StartRun(); practice.setQuestionId(q.getId()); practice.setRequestId("practice_01");
        Map<String, Object> single = objectives.start(practice); ObjectiveDTO.Answer answer = new ObjectiveDTO.Answer(); answer.setQuestionId(q.getId()); answer.setSelectedAnswers(Arrays.asList("42", "OK")); objectives.answer((Long) single.get("id"), answer);
        assertEquals(0, manager.run(id(run)).get("answeredCount")); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM objective_run", Integer.class));
    }
    @Test void deferredCodeOnlyJudgesOnceAtFinishAndTotalsWaitForAsyncResults() {
        ObjectiveDTO.Question q = blank(); Map<String, Object> run = start(paper("AFTER_FINISH", q).getId(), "start_0002"); Long id = id(run);
        manager.answer(id, objective(q.getId(), " 0x2a ", "wrong")); manager.answer(id, code(q.getId(), "code_0001"));
        verify(programs, never()).submit(anyLong(), anyLong(), anyString(), anyString(), anyString());
        Map<String, Object> finished = manager.finish(id); assertFalse(finished.containsKey("score")); assertEquals(3.0, item(finished, 0).get("score")); assertEquals(false, finished.get("gradingComplete"));
        manager.finish(id); verify(programs, times(1)).submit(anyLong(), anyLong(), anyString(), anyString(), anyString());
        judge().setStatus(0); assertEquals(23.0, manager.run(id).get("score"));
        assertThrows(ObjectiveException.class, () -> manager.answer(id, objective(q.getId(), "42", "OK")));
    }
    @Test void frozenItemsAndScoringSurviveQuestionAndPaperEditsWhileReleaseRulesRemainLive() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("AFTER_FINISH", q); Map<String, Object> old = start(p.getId(), "start_old1");
        q.setTitle("修改后的标题"); q.getBlanks().get(0).setScore(50); q.getBlanks().get(0).setAnswers(Collections.singletonList("different")); objectives.saveQuestion(q);
        p.setProgramMode("LIVE"); p.setScoreRule("LAST"); p.setAnswerPolicy("NEVER"); manager.save(p);
        Long id = id(old); manager.answer(id, objective(q.getId(), "42", "OK")); manager.finish(id);
        Map<String, Object> hidden = manager.run(id); assertEquals("AFTER_FINISH", hidden.get("programMode")); assertEquals("BEST", hidden.get("scoreRule")); assertEquals("填空题", item(hidden, 0).get("title")); assertEquals(10, item(hidden, 0).get("maxScore")); assertFalse(hidden.containsKey("score"));
        p.setAnswerPolicy("AFTER_FINISH"); manager.save(p); assertEquals(10.0, manager.run(id).get("score"));
        Map<String, Object> fresh = start(p.getId(), "start_new1"); assertEquals(57, item(fresh, 0).get("maxScore")); assertEquals("LIVE", fresh.get("programMode"));
    }
    @Test void liveSubmissionsAreIdempotentAndScoreBestOrLastFromFrozenRules() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("LIVE", q); Long id = id(start(p.getId(), "start_live"));
        PaperDTO.Answer first = code(q.getId(), "submit_001"); manager.submit(id, first); manager.submit(id, first);
        assertEquals(1, submissions.values().stream().mapToInt(List::size).sum()); judge().setStatus(0);
        assertEquals(20.0, item(manager.run(id), 1).get("score")); assertFalse(manager.run(id).containsKey("score"));
        manager.submit(id, code(q.getId(), "submit_002")); submissions.values().iterator().next().get(1).setStatus(-1);
        assertEquals(20.0, item(manager.finish(id), 1).get("score"));
        p.setScoreRule("LAST"); manager.save(p); Long last = id(start(p.getId(), "start_last")); manager.submit(last, code(q.getId(), "submit_003"));
        List<Judge> list = submissions.get(snapshots.keySet().stream().max(Long::compareTo).get()); list.get(0).setStatus(0);
        manager.submit(last, code(q.getId(), "submit_004")); list.get(1).setStatus(-1); assertEquals(0.0, manager.finish(last).get("score"));
        assertEquals(20.0, manager.run(id).get("score"));
    }
    @Test void scheduledAnswersAndIndependentExplanationsStayHiddenUntilConditionsMatch() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("AFTER_FINISH", q); p.setAnswerPolicy("AT_TIME"); p.setAnswerReleaseAt(time + 100); p.setExplanationPolicy("NEVER"); manager.save(p);
        Long id = id(start(p.getId(), "start_time")); manager.answer(id, objective(q.getId(), "42", "OK"));
        assertFalse(manager.run(id).containsKey("score")); manager.finish(id); assertFalse(manager.run(id).containsKey("score"));
        time += 100; Map<String, Object> visible = manager.run(id); assertEquals(10.0, visible.get("score")); assertTrue(item(visible, 0).containsKey("correctAnswers")); assertFalse(item(visible, 0).containsKey("explanation"));
    }
    @Test void ownershipPublicationVersionAndInvalidQuestionKeysAreEnforced() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("LIVE", q); Long id = id(start(p.getId(), "start_auth"));
        uid = "bob"; all = false; assertThrows(ObjectiveException.class, () -> manager.run(id)); assertThrows(ObjectiveException.class, () -> manager.detail(p.getId())); assertEquals(0, manager.histories(1).get("total") instanceof Number ? ((Number) manager.histories(1).get("total")).intValue() : -1);
        uid = "alice"; assertThrows(ObjectiveException.class, () -> manager.answer(id, objective(999L, "42", "OK")));
        p.setVersion(0); assertEquals(409, assertThrows(ObjectiveException.class, () -> manager.save(p)).getStatus());
        p.setVersion(1); p.setPublished(false); manager.save(p); assertThrows(ObjectiveException.class, () -> manager.run(id)); assertThrows(ObjectiveException.class, () -> start(p.getId(), "start_draft"));
    }
    @Test void repeatedStartReusesRunAndSnapshotAndNonceCannotTargetAnotherPaper() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("LIVE", q); Long a = id(start(p.getId(), "start_same")); Long b = id(start(p.getId(), "start_same")); assertEquals(a, b);
        verify(programs, times(1)).freezeForCollection(anyLong(), anyString(), anyString(), anyLong());
        PaperDTO.Paper other = paper("LIVE", q); assertThrows(ObjectiveException.class, () -> start(other.getId(), "start_same"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM paper_run", Integer.class));
    }
    @Test void duplicateReferencesInvalidBlankAnswersAndWrongProgramModeAreRejected() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("AFTER_FINISH", q); p.setItems(Arrays.asList(p.getItems().get(0), p.getItems().get(0))); assertThrows(ObjectiveException.class, () -> manager.save(p));
        Long id = id(start(paper("AFTER_FINISH", q).getId(), "start_invalid")); assertThrows(ObjectiveException.class, () -> manager.answer(id, objective(q.getId(), "42"))); assertThrows(ObjectiveException.class, () -> manager.submit(id, code(q.getId(), "submit_wrong")));
        assertThrows(ObjectiveException.class, () -> manager.start(null));
    }
    @Test void failedFreezingRollsBackRunInsertionAndDoesNotLeavePartialPaper() {
        ObjectiveDTO.Question q = blank(); PaperDTO.Paper p = paper("AFTER_FINISH", q);
        when(programs.freezeForCollection(anyLong(), anyString(), anyString(), anyLong())).thenThrow(new ObjectiveException(400, "缺少测试数据"));
        assertThrows(ObjectiveException.class, () -> start(p.getId(), "start_fail")); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM paper_run", Integer.class));
    }

    @Test void judgeServiceFailureDoesNotBecomeZeroScoreAndCanBeRetriedWithoutReopeningAnswers() {
        ObjectiveDTO.Question q = blank(); Long id = id(start(paper("AFTER_FINISH", q).getId(), "start_retry"));
        manager.answer(id, code(q.getId(), "code_retry")); manager.finish(id); judge().setStatus(4);
        Map<String, Object> failed = manager.run(id); assertEquals(true, failed.get("gradingFailed")); assertFalse(failed.containsKey("score"));
        doAnswer(call -> { judge().setStatus(5); return null; }).when(programs).retry(id, "alice");
        assertEquals("FINISHED", manager.retry(id).get("state")); assertEquals(false, manager.run(id).get("gradingComplete"));
        judge().setStatus(0); assertEquals(20.0, manager.run(id).get("score"));
        uid = "bob"; assertThrows(ObjectiveException.class, () -> manager.retry(id)); verify(programs, never()).retry(id, "bob");
    }

    @Test void publishedPaperCanUsePrivateObjectivesWithoutOpeningStandalonePractice() {
        ObjectiveDTO.Question q = blank(); q.setPublished(false); q.setPracticeEnabled(false); q = objectives.saveQuestion(q);
        PaperDTO.Paper p = paper("AFTER_FINISH", q);
        assertThrows(ObjectiveException.class, () -> objectives.publishedQuestionSnapshot(p.getItems().get(0).getReferenceId()));
        Map<String, Object> run = start(p.getId(), "private_paper");
        assertEquals("填空题", item(run, 0).get("title"));
        assertFalse(item(run, 0).containsKey("correctAnswers"));
        assertFalse(objectives.question(q.getId()).getPublished());
    }

    @Test void unifiedCollectionCataloguePaginatesBothSourcesAndScopesOwnersAndKinds() {
        ObjectiveDTO.Question q = blank(); paper("LIVE", q);
        for (int i = 0; i < 22; i++) {
            ObjectiveDTO.ProblemSet set = new ObjectiveDTO.ProblemSet(); set.setTitle("题集 " + i); set.setMode("PRACTICE");
            set.setQuestionIds(Collections.singletonList(q.getId())); set.setPublished(false); set.setAnswerPolicy("AFTER_ANSWER"); set.setExplanationPolicy("NEVER"); objectives.saveSet(set);
        }
        Map<String, Object> first = manager.collections(1, "", "");
        assertEquals(23L, first.get("total")); assertEquals(20, ((List<?>) first.get("records")).size());
        assertEquals(3, ((List<?>) manager.collections(2, "", "").get("records")).size());
        assertEquals(22L, manager.collections(1, "", "PRACTICE").get("total"));
        assertEquals(1L, manager.collections(1, "", "PAPER").get("total"));
        assertEquals(0L, manager.collections(1, "不存在", "").get("total"));
        assertFalse(((Map<?, ?>) ((List<?>) first.get("records")).get(0)).containsKey("config"));
        all = false; uid = "bob"; assertEquals(0L, manager.collections(1, "", "").get("total"));
    }
}
