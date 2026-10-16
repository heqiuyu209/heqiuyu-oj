package top.hcode.hoj.manager.oj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.mapper.*;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real mapper/transaction tests, using an isolated in-memory database, no HOJ services or user data. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ObjectiveManagerTest.Config.class)
class ObjectiveManagerTest {
    private static final AtomicReference<String> UID = new AtomicReference<>("alice");
    private static boolean manageAll = true;
    private static long time = 1000000L;

    @Autowired ObjectiveManager manager;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:hoj_objective;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            GlobalConfig global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
            factory.setGlobalConfig(global);
            factory.setPlugins(new PaginationInterceptor());
            SqlSessionFactory result = factory.getObject();
            result.getConfiguration().addMapper(ObjectiveQuestionMapper.class);
            result.getConfiguration().addMapper(ObjectiveQuestionVersionMapper.class);
            result.getConfiguration().addMapper(ObjectiveSetMapper.class);
            result.getConfiguration().addMapper(ObjectiveRunMapper.class);
            return result;
        }
        @Bean ObjectiveQuestionMapper questionMapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(ObjectiveQuestionMapper.class); }
        @Bean ObjectiveQuestionVersionMapper versionMapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(ObjectiveQuestionVersionMapper.class); }
        @Bean ObjectiveSetMapper setMapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(ObjectiveSetMapper.class); }
        @Bean ObjectiveRunMapper runMapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(ObjectiveRunMapper.class); }
        @Bean TagMapper tagMapper() {
            return (TagMapper) java.lang.reflect.Proxy.newProxyInstance(TagMapper.class.getClassLoader(),
                    new Class<?>[]{TagMapper.class}, (proxy, method, args) -> {
                        if ("selectBatchIds".equals(method.getName())) return Collections.emptyList();
                        if ("toString".equals(method.getName())) return "isolated-tag-stub";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Bean ObjectiveManager manager() {
            return new ObjectiveManager() {
                @Override protected String currentUid() { return UID.get(); }
                @Override protected boolean canManageAll() { return manageAll; }
                @Override protected long now() { return time; }
            };
        }
    }

    @BeforeEach void reset() throws Exception {
        UID.set("alice"); manageAll = true; time = 1000000L;
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
            jdbc.execute("DROP ALL OBJECTS");
            String schema = StreamUtils.copyToString(new ClassPathResource("objective.sql").getInputStream(), StandardCharsets.UTF_8)
                    .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private ObjectiveDTO.Question question(String type, String... correct) {
        ObjectiveDTO.Question dto = new ObjectiveDTO.Question();
        dto.setTitle("算法概念");
        dto.setStem("选择符合题意的选项");
        dto.setQuestionType(type);
        List<ObjectiveDTO.Option> options = new ArrayList<>();
        for (String id : Arrays.asList("A", "B", "C")) {
            ObjectiveDTO.Option option = new ObjectiveDTO.Option();
            option.setId(id); option.setText("选项 " + id); options.add(option);
        }
        dto.setOptions(options); dto.setCorrectAnswers(Arrays.asList(correct));
        dto.setExplanation("标准解析");
        dto.setPublished(true);
        return manager.saveQuestion(dto);
    }

    private ObjectiveDTO.ProblemSet problemSet(String mode, ObjectiveDTO.Question... questions) {
        ObjectiveDTO.ProblemSet dto = new ObjectiveDTO.ProblemSet();
        dto.setTitle("基础题集"); dto.setMode(mode); dto.setPublished(true);
        List<Long> ids = new ArrayList<>();
        for (ObjectiveDTO.Question question : questions) ids.add(question.getId());
        dto.setQuestionIds(ids);
        dto.setAnswerPolicy("PAPER".equals(mode) ? "AFTER_FINISH" : "AFTER_ANSWER");
        dto.setExplanationPolicy(dto.getAnswerPolicy());
        return dto;
    }

    private Long save(ObjectiveDTO.ProblemSet dto) {
        Map<String, Object> set = manager.saveSet(dto);
        dto.setId((Long) set.get("id")); dto.setVersion((Integer) set.get("version"));
        return dto.getId();
    }

    private Map<String, Object> start(Long setId, String key) {
        ObjectiveDTO.StartRun dto = new ObjectiveDTO.StartRun();
        dto.setSetId(setId); dto.setRequestId(key);
        return manager.start(dto);
    }

    private Map<String, Object> single(Long questionId, String key) {
        ObjectiveDTO.StartRun dto = new ObjectiveDTO.StartRun();
        dto.setQuestionId(questionId); dto.setRequestId(key);
        return manager.start(dto);
    }

    private Map<String, Object> answer(Long runId, Long questionId, String... selected) {
        ObjectiveDTO.Answer dto = new ObjectiveDTO.Answer();
        dto.setQuestionId(questionId); dto.setSelectedAnswers(Arrays.asList(selected));
        return manager.answer(runId, dto);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> questions(Map<String, Object> run) {
        return (List<Map<String, Object>>) run.get("questions");
    }

    private void hidden(Map<String, Object> run) {
        assertFalse(run.containsKey("score"));
        assertFalse(run.containsKey("correctCount"));
        for (Map<String, Object> question : questions(run)) {
            assertFalse(question.containsKey("correctAnswers"));
            assertFalse(question.containsKey("explanation"));
            assertFalse(question.containsKey("correct"));
            assertFalse(question.containsKey("score"));
        }
    }

    @Test void practiceGradesOnServerAndRetriesDoNotCreateExtraRuns() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        Long set = save(problemSet("PRACTICE", question));
        Map<String, Object> run = start(set, "practice_1");
        hidden(run);
        Long id = (Long) run.get("id");
        Map<String, Object> answered = answer(id, question.getId(), "A");
        assertEquals(true, questions(answered).get(0).get("correct"));
        assertEquals(1, answered.get("answeredCount"));
        assertEquals(1, answer(id, question.getId(), "A").get("answeredCount"));
        assertEquals(id, start(set, "practice_1").get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM objective_run", Integer.class));
        assertEquals(409, assertThrows(ObjectiveException.class, () -> answer(id, question.getId(), "B")).getStatus());
    }

    @Test void multipleChoiceRequiresExactSetAndRejectsUnknownOrDuplicateOptions() {
        ObjectiveDTO.Question question = question("MULTIPLE_CHOICE", "C", "A");
        Long set = save(problemSet("PRACTICE", question));
        Long id = (Long) start(set, "multiple_1").get("id");
        assertThrows(ObjectiveException.class, () -> answer(id, question.getId(), "A", "A"));
        assertThrows(ObjectiveException.class, () -> answer(id, question.getId(), "Z"));
        assertEquals(true, questions(answer(id, question.getId(), "C", "A")).get(0).get("correct"));
        Long other = (Long) start(set, "multiple_2").get("id");
        assertEquals(false, questions(answer(other, question.getId(), "A")).get(0).get("correct"));
    }

    @Test void trueFalseUsesFixedOptionsAndSingleChoiceCannotAcceptTwoAnswers() {
        ObjectiveDTO.Question question = question("TRUE_FALSE", "TRUE");
        assertEquals(2, question.getOptions().size());
        Long id = (Long) start(save(problemSet("PRACTICE", question)), "boolean_1").get("id");
        assertThrows(ObjectiveException.class, () -> answer(id, question.getId(), "TRUE", "FALSE"));
        assertEquals(true, questions(answer(id, question.getId(), "TRUE")).get(0).get("correct"));
    }

    @Test void paperDraftsAreEditableAndNeverLeakFeedbackBeforeFinishing() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.ProblemSet set = problemSet("PAPER", question);
        set.setAnswerPolicy("AFTER_ANSWER"); set.setExplanationPolicy("AT_TIME"); set.setExplanationReleaseAt(1L);
        Long id = (Long) start(save(set), "paper_0001").get("id");
        hidden(answer(id, question.getId(), "B"));
        hidden(answer(id, question.getId(), "A"));
        hidden(manager.run(id));
        Map<String, Object> result = manager.finish(id);
        assertEquals(1, result.get("score"));
        assertEquals("FINISHED", result.get("state"));
        assertEquals(1, manager.finish(id).get("score"));
        assertEquals(409, assertThrows(ObjectiveException.class, () -> answer(id, question.getId(), "B")).getStatus());
    }

    @Test void paperCanClearDraftAndFinishWithUnansweredQuestions() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        Long id = (Long) start(save(problemSet("PAPER", question)), "paper_0002").get("id");
        answer(id, question.getId(), "A");
        assertEquals(0, answer(id, question.getId()).get("answeredCount"));
        assertEquals(0, manager.finish(id).get("score"));
        assertEquals(false, questions(manager.run(id)).get(0).get("correct"));
    }

    @Test void afterFinishWithholdsPracticeFeedbackUntilWholeRunIsClosed() {
        ObjectiveDTO.Question q1 = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.Question q2 = question("TRUE_FALSE", "FALSE");
        ObjectiveDTO.ProblemSet set = problemSet("PRACTICE", q1, q2);
        set.setAnswerPolicy("AFTER_FINISH"); set.setExplanationPolicy("AFTER_FINISH");
        Long id = (Long) start(save(set), "practice_2").get("id");
        hidden(answer(id, q1.getId(), "A"));
        hidden(answer(id, q2.getId(), "FALSE"));
        assertEquals(2, manager.finish(id).get("score"));
    }

    @Test void livePoliciesControlOldRunsAndAnswerExplanationAreIndependent() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.ProblemSet set = problemSet("PRACTICE", question);
        set.setAnswerPolicy("NEVER"); set.setExplanationPolicy("NEVER");
        Long id = (Long) start(save(set), "policy_001").get("id");
        hidden(answer(id, question.getId(), "A"));
        manager.finish(id);
        set.setAnswerPolicy("AT_TIME"); set.setAnswerReleaseAt(time + 10);
        save(set); hidden(manager.run(id));
        time += 10;
        Map<String, Object> visible = questions(manager.run(id)).get(0);
        assertEquals(true, visible.get("correct"));
        assertFalse(visible.containsKey("explanation"));
        set.setExplanationPolicy("AFTER_FINISH"); save(set);
        assertEquals("标准解析", questions(manager.run(id)).get(0).get("explanation"));
        set.setAnswerPolicy("NEVER"); save(set);
        assertFalse(questions(manager.run(id)).get(0).containsKey("correctAnswers"));
        assertNull(jdbc.queryForObject("SELECT answer_release_at FROM objective_set WHERE id = ?", Long.class, set.getId()));
    }

    @Test void scheduledReleaseRequiresSubmissionAndHonorsExactBoundary() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.ProblemSet set = problemSet("PRACTICE", question);
        set.setAnswerPolicy("AT_TIME"); set.setAnswerReleaseAt(time);
        set.setExplanationPolicy("AT_TIME"); set.setExplanationReleaseAt(time);
        Long id = (Long) start(save(set), "timed_0001").get("id");
        hidden(manager.run(id));
        assertEquals(true, questions(answer(id, question.getId(), "A")).get(0).get("answerVisible"));
    }

    @Test void changingQuestionCannotRewriteExistingRunAndStaleEditsAreRejected() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        Long set = save(problemSet("PRACTICE", question));
        Long oldId = (Long) start(set, "snapshot_1").get("id");
        ObjectiveDTO.Question stale = manager.question(question.getId());
        question.setTitle("新版本"); question.setCorrectAnswers(Collections.singletonList("B"));
        manager.saveQuestion(question);
        assertEquals(409, assertThrows(ObjectiveException.class, () -> manager.saveQuestion(stale)).getStatus());
        Map<String, Object> old = questions(answer(oldId, question.getId(), "A")).get(0);
        assertEquals(1, old.get("version")); assertEquals("算法概念", old.get("title")); assertEquals(true, old.get("correct"));
        Long newId = (Long) start(set, "snapshot_2").get("id");
        assertEquals(2, questions(answer(newId, question.getId(), "B")).get(0).get("version"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM objective_question_version", Integer.class));
    }

    @Test void privateRunsAndOtherAdministratorsQuestionsCannotBeAccessed() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        Long id = (Long) start(save(problemSet("PRACTICE", question)), "owner_0001").get("id");
        UID.set("bob"); manageAll = false;
        assertEquals(404, assertThrows(ObjectiveException.class, () -> manager.run(id)).getStatus());
        assertThrows(ObjectiveException.class, () -> answer(id, question.getId(), "A"));
        assertThrows(ObjectiveException.class, () -> manager.finish(id));
        assertEquals(403, assertThrows(ObjectiveException.class, () -> manager.question(question.getId())).getStatus());
        assertEquals(0L, manager.questions(1, 20, "", "").get("total"));
        assertEquals(0L, manager.runs(1, 20).get("total"));
    }

    @Test void unpublishedSetsRejectStartsAndExistingRunReads() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.ProblemSet set = problemSet("PRACTICE", question);
        Long id = (Long) start(save(set), "publish_01").get("id");
        set.setPublished(false); save(set);
        assertEquals(403, assertThrows(ObjectiveException.class, () -> start(set.getId(), "publish_02")).getStatus());
        assertThrows(ObjectiveException.class, () -> manager.run(id));
    }

    @Test void setModeAndRequestIdCannotBeUsedToBypassWholePaperRules() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.ProblemSet set = problemSet("PAPER", question);
        save(set); start(set.getId(), "scope_0001");
        set.setMode("PRACTICE");
        assertThrows(ObjectiveException.class, () -> save(set));
        ObjectiveDTO.ProblemSet contest = problemSet("CONTEST", question);
        assertThrows(ObjectiveException.class, () -> save(contest));
        Long other = save(problemSet("PRACTICE", question));
        assertEquals(409, assertThrows(ObjectiveException.class, () -> start(other, "scope_0001")).getStatus());
    }

    @Test void historiesNeverLeakResultsAndQuestionsOutsideSnapshotAreRejected() {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.Question outside = question("SINGLE_CHOICE", "B");
        Long id = (Long) start(save(problemSet("PAPER", question)), "history_01").get("id");
        assertThrows(ObjectiveException.class, () -> answer(id, outside.getId(), "B"));
        String history = manager.runs(1, 20).toString();
        assertFalse(history.contains("correct")); assertFalse(history.contains("score"));
        assertFalse(history.contains("snapshot")); assertFalse(history.contains("标准解析"));
    }

    @Test void concurrentPracticeSubmissionsCommitExactlyOneAnswer() throws Exception {
        ObjectiveDTO.Question question = question("SINGLE_CHOICE", "A");
        Long id = (Long) start(save(problemSet("PRACTICE", question)), "parallel_1").get("id");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch begin = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (String choice : Arrays.asList("A", "B")) {
                futures.add(executor.submit(() -> {
                    ready.countDown(); begin.await();
                    try { answer(id, question.getId(), choice); return true; }
                    catch (ObjectiveException e) { assertEquals(409, e.getStatus()); return false; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); begin.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, manager.run(id).get("answeredCount"));
        } finally { executor.shutdownNow(); }
    }

    @Test void publishedQuestionsCanBePracticedWithoutCreatingAnySet() {
        List<ObjectiveDTO.Question> items = Arrays.asList(question("SINGLE_CHOICE", "A"),
                question("MULTIPLE_CHOICE", "A", "C"), question("TRUE_FALSE", "TRUE"));
        UID.set("student"); manageAll = false;
        assertEquals(0L, manager.questions(1, 20, "", "").get("total"));
        assertEquals(3L, manager.practiceQuestions(1, 20, "", "").get("total"));
        assertEquals(1L, manager.practiceQuestions(1, 20, "", "TRUE_FALSE").get("total"));
        assertEquals(0L, manager.practiceQuestions(1, 20, "missing", "").get("total"));
        String listing = manager.practiceQuestions(1, 20, "", "").toString();
        assertFalse(listing.contains("correctAnswers")); assertFalse(listing.contains("explanation"));
        for (ObjectiveDTO.Question item : items) {
            Map<String, Object> run = single(item.getId(), "single_" + item.getId());
            hidden(run);
            assertNull(run.get("setId")); assertEquals(item.getId(), run.get("questionId"));
            assertEquals(1, questions(run).size());
            Map<String, Object> result = answer((Long) run.get("id"), item.getId(), item.getCorrectAnswers().toArray(new String[0]));
            assertEquals("FINISHED", result.get("state")); assertEquals(1, result.get("score"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM objective_set", Integer.class));
        assertTrue(manager.runs(1, 20).toString().contains("questionId="));
    }

    @Test void standaloneVisibilityDoesNotGrantAccessToPaperOnlyOrUnpublishedQuestions() {
        ObjectiveDTO.Question item = question("SINGLE_CHOICE", "A");
        Long runId = (Long) single(item.getId(), "single_visibility").get("id");
        item.setPracticeEnabled(false); manager.saveQuestion(item);
        assertEquals(0L, manager.practiceQuestions(1, 20, "", "").get("total"));
        assertEquals(403, assertThrows(ObjectiveException.class, () -> single(item.getId(), "single_blocked")).getStatus());
        assertEquals(403, assertThrows(ObjectiveException.class, () -> manager.run(runId)).getStatus());
        Long paper = save(problemSet("PAPER", item));
        Long paperRun = (Long) start(paper, "paper_only_1").get("id");
        hidden(answer(paperRun, item.getId(), "A"));
        assertEquals(1, manager.finish(paperRun).get("score"));
        item.setPracticeEnabled(true); item.setPublished(false); manager.saveQuestion(item);
        assertEquals(0L, manager.practiceQuestions(1, 20, "", "").get("total"));
        assertThrows(ObjectiveException.class, () -> single(item.getId(), "single_unpublished"));
    }

    @Test void standalonePoliciesAreLiveAndIndependentFromTheFrozenQuestionVersion() {
        ObjectiveDTO.Question item = question("SINGLE_CHOICE", "A");
        item.setAnswerPolicy("NEVER"); item.setExplanationPolicy("NEVER"); manager.saveQuestion(item);
        Long id = (Long) single(item.getId(), "single_policy").get("id");
        hidden(answer(id, item.getId(), "A"));
        item.setAnswerPolicy("AT_TIME"); item.setAnswerReleaseAt(time + 1);
        item.setExplanationPolicy("NEVER"); item.setCorrectAnswers(Collections.singletonList("B")); manager.saveQuestion(item);
        hidden(manager.run(id)); time++;
        Map<String, Object> result = manager.run(id);
        assertEquals(1, result.get("score"));
        assertEquals(Collections.singletonList("A"), questions(result).get(0).get("correctAnswers"));
        assertFalse(questions(result).get(0).containsKey("explanation"));
        item.setExplanationPolicy("AFTER_ANSWER"); manager.saveQuestion(item);
        assertEquals("标准解析", questions(manager.run(id)).get(0).get("explanation"));
        Long next = (Long) single(item.getId(), "single_new_version").get("id");
        assertEquals(1, answer(next, item.getId(), "B").get("score"));
    }

    @Test void standaloneNonceCannotBeReusedAcrossSourcesAndBothSourcesCannotBeSubmitted() {
        ObjectiveDTO.Question first = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.Question second = question("SINGLE_CHOICE", "B");
        Long set = save(problemSet("PAPER", first));
        Long runId = (Long) single(first.getId(), "single_nonce").get("id");
        assertEquals(runId, single(first.getId(), "single_nonce").get("id"));
        assertEquals(409, assertThrows(ObjectiveException.class, () -> single(second.getId(), "single_nonce")).getStatus());
        assertEquals(409, assertThrows(ObjectiveException.class, () -> start(set, "single_nonce")).getStatus());
        ObjectiveDTO.StartRun invalid = new ObjectiveDTO.StartRun();
        invalid.setQuestionId(first.getId()); invalid.setSetId(set); invalid.setRequestId("single_both");
        assertThrows(ObjectiveException.class, () -> manager.start(invalid));
        invalid.setQuestionId(null); invalid.setSetId(null);
        assertThrows(ObjectiveException.class, () -> manager.start(invalid));
        answer(runId, first.getId(), "A");
        UID.set("bob"); manageAll = false;
        assertEquals(404, assertThrows(ObjectiveException.class, () -> manager.run(runId)).getStatus());
        assertThrows(ObjectiveException.class, () -> answer(runId, first.getId(), "A"));
        assertThrows(ObjectiveException.class, () -> manager.finish(runId));
    }

    @Test void standalonePoliciesNeverOverridePaperReleaseRules() {
        ObjectiveDTO.Question item = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.ProblemSet paper = problemSet("PAPER", item);
        paper.setAnswerPolicy("NEVER"); paper.setExplanationPolicy("NEVER");
        Long id = (Long) start(save(paper), "paper_isolation").get("id");
        hidden(answer(id, item.getId(), "A")); hidden(manager.finish(id));
        Long direct = (Long) single(item.getId(), "single_isolation").get("id");
        assertEquals(1, answer(direct, item.getId(), "A").get("score"));
        hidden(manager.run(id));
    }

    @Test void legacyQuestionJsonUsesDefaultStandalonePolicies() throws Exception {
        ObjectiveDTO.Question item = question("SINGLE_CHOICE", "A");
        String payload = jdbc.queryForObject("SELECT content_json FROM objective_question WHERE id = ?", String.class, item.getId());
        Map<?, ?> legacy = new ObjectMapper().readValue(payload, Map.class);
        for (String key : Arrays.asList("practiceEnabled", "answerPolicy", "explanationPolicy", "answerReleaseAt", "explanationReleaseAt")) legacy.remove(key);
        jdbc.update("UPDATE objective_question SET content_json = ? WHERE id = ?", new ObjectMapper().writeValueAsString(legacy), item.getId());
        assertEquals("AFTER_ANSWER", manager.question(item.getId()).getAnswerPolicy());
        Long id = (Long) single(item.getId(), "single_legacy").get("id");
        assertEquals(1, answer(id, item.getId(), "A").get("score"));
    }

    @Test void neighborsMatchListOrderAndFiltersWithoutExposingHiddenQuestionsOrAnswers() {
        ObjectiveDTO.Question oldest = question("SINGLE_CHOICE", "A");
        ObjectiveDTO.Question hidden = question("SINGLE_CHOICE", "B");
        hidden.setPublished(false); manager.saveQuestion(hidden);
        ObjectiveDTO.Question middle = question("TRUE_FALSE", "TRUE");
        ObjectiveDTO.Question paperOnly = question("SINGLE_CHOICE", "A");
        paperOnly.setPracticeEnabled(false); manager.saveQuestion(paperOnly);
        ObjectiveDTO.Question newest = question("SINGLE_CHOICE", "A");
        Map<String, Object> adjacent = manager.neighbors(middle.getId(), "算法", "");
        assertEquals(newest.getId(), ((Map<?, ?>) adjacent.get("previous")).get("id"));
        assertEquals(oldest.getId(), ((Map<?, ?>) adjacent.get("next")).get("id"));
        assertEquals(new HashSet<>(Arrays.asList("id", "title", "questionType")),
                ((Map<?, ?>) adjacent.get("next")).keySet());
        assertNull(manager.neighbors(newest.getId(), "", "").get("previous"));
        assertNull(manager.neighbors(oldest.getId(), "", "").get("next"));
        assertNull(manager.neighbors(newest.getId(), "不存在", "").get("next"));
        assertEquals(middle.getId(), ((Map<?, ?>) manager.neighbors(newest.getId(), "", "TRUE_FALSE").get("next")).get("id"));
        assertThrows(ObjectiveException.class, () -> manager.neighbors(hidden.getId(), "", ""));
        assertThrows(ObjectiveException.class, () -> manager.neighbors(paperOnly.getId(), "", ""));
    }

    @Test void sourceIsValidatedAndFrozenWithTheQuestionSnapshot() {
        ObjectiveDTO.Question item = question("SINGLE_CHOICE", "A");
        item.setSource("  计算机基础 · 2025 年真题  "); manager.saveQuestion(item);
        Long id = (Long) single(item.getId(), "source_snapshot").get("id");
        assertEquals("计算机基础 · 2025 年真题", questions(manager.run(id)).get(0).get("source"));
        item.setSource("新来源"); manager.saveQuestion(item);
        assertEquals("计算机基础 · 2025 年真题", questions(manager.run(id)).get(0).get("source"));
        item.setSource(String.join("", Collections.nCopies(201, "x")));
        assertThrows(ObjectiveException.class, () -> manager.saveQuestion(item));
        hidden(manager.run(id));
    }

    private ObjectiveDTO.Question blanks() {
        ObjectiveDTO.Question q = new ObjectiveDTO.Question(); q.setTitle("结果填空"); q.setStem("计算两个结果"); q.setQuestionType("FILL_BLANK"); q.setPublished(true);
        ObjectiveDTO.Blank a = new ObjectiveDTO.Blank(); a.setId("a"); a.setScore(3); a.setAnswers(Arrays.asList("42", "0x2a")); a.setCaseSensitive(false);
        ObjectiveDTO.Blank b = new ObjectiveDTO.Blank(); b.setId("b"); b.setScore(7); b.setAnswers(Collections.singletonList("OK")); b.setTrim(false);
        q.setBlanks(Arrays.asList(a, b)); return manager.saveQuestion(q);
    }

    @Test void fillBlanksGradeEquivalentAnswersPerBlankAndKeepOrder() {
        ObjectiveDTO.Question q = blanks(); Long id = (Long) single(q.getId(), "blank_partial").get("id");
        Map<String, Object> result = answer(id, q.getId(), " 0X2A ", " OK ");
        assertEquals(3, result.get("score")); assertEquals(10, result.get("maxScore")); assertEquals(false, questions(result).get(0).get("correct"));
        Long full = (Long) single(q.getId(), "blank_full").get("id"); assertEquals(10, answer(full, q.getId(), "42", "OK").get("score"));
        assertEquals(Arrays.asList("42", "42"), manager.normalize(q, Arrays.asList("42", "42"), false));
    }

    @Test void fillBlankStandardsAreHiddenAndInvalidAnswerCountsAreRejected() {
        ObjectiveDTO.Question q = blanks(); Map<String, Object> run = single(q.getId(), "blank_hidden");
        @SuppressWarnings("unchecked") List<Map<String, Object>> publicBlanks = (List<Map<String, Object>>) questions(run).get(0).get("blanks");
        assertEquals(new HashSet<>(Arrays.asList("id", "label", "score")), publicBlanks.get(0).keySet());
        assertFalse(questions(run).get(0).containsKey("correctAnswers"));
        assertThrows(ObjectiveException.class, () -> answer((Long) run.get("id"), q.getId(), "42"));
        assertThrows(ObjectiveException.class, () -> answer((Long) run.get("id"), q.getId(), "", ""));
        q.getBlanks().get(0).setScore(0); assertThrows(ObjectiveException.class, () -> manager.saveQuestion(q));
    }

    @Test void fillBlankPaperCanSaveEmptyDraftAndOldAnswersKeepOldPoints() {
        ObjectiveDTO.Question q = blanks(); Long set = save(problemSet("PAPER", q)); Long id = (Long) start(set, "blank_paper").get("id");
        answer(id, q.getId(), "", ""); assertFalse(manager.run(id).containsKey("score"));
        answer(id, q.getId(), "42", "OK"); q.getBlanks().get(0).setScore(30); manager.saveQuestion(q);
        assertEquals(10, manager.finish(id).get("score")); assertEquals(10, manager.run(id).get("maxScore"));
    }

    @Test void standaloneAutoFinishIsIdempotentAndRejectsChangesAfterSubmission() {
        ObjectiveDTO.Question item = question("SINGLE_CHOICE", "A");
        Long id = (Long) single(item.getId(), "single_finished").get("id");
        assertEquals("FINISHED", answer(id, item.getId(), "A").get("state"));
        assertEquals(1, answer(id, item.getId(), "A").get("score"));
        assertEquals(1, manager.finish(id).get("score"));
        assertEquals(409, assertThrows(ObjectiveException.class, () -> answer(id, item.getId(), "B")).getStatus());
        assertEquals(id, single(item.getId(), "single_finished").get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM objective_run", Integer.class));
    }
}
