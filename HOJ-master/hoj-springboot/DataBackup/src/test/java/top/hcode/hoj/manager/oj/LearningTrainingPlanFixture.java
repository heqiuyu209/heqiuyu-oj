package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.StreamUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.*;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;

class LearningTrainingPlanFixture {
    final JdbcTemplate jdbc;
    final DriverManagerDataSource source;
    final ThreadLocal<String> actor = ThreadLocal.withInitial(() -> "alice");
    final LearningTrainingPlanManager manager;
    final LearningRecommendationManager recommendations = mock(LearningRecommendationManager.class);
    final LearningCandidateStore candidates = mock(LearningCandidateStore.class);
    final AiAccessGuard access = mock(AiAccessGuard.class);
    final Map<String,LearningCandidateStore.Candidate> visible = new LinkedHashMap<>();
    final Set<String> attempted = new HashSet<>(), solved = new HashSet<>();
    final Map<String,Object> verified = new LinkedHashMap<>();
    LearningTrainingPlanFixture(String url, String user, String password) throws Exception {
        source = new DriverManagerDataSource(url, user, password); jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0),('inactive',1)");
        String ddl;
        try (java.io.InputStream stream = new ClassPathResource("learning-training-plan.sql").getInputStream()) { ddl = StreamUtils.copyToString(stream,StandardCharsets.UTF_8); }
        // H2 1.4 does not parse MySQL's collation clause; MySQL runs the exact source unchanged.
        if (url.startsWith("jdbc:h2:")) ddl = ddl.replace(" COLLATE=utf8mb4_unicode_ci", "");
        try (Connection c = source.getConnection()) { ByteArrayResource script = new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)); ScriptUtils.executeSqlScript(c, script); ScriptUtils.executeSqlScript(c, script); }
        manager = new LearningTrainingPlanManager() { @Override protected String uid() { return actor.get(); } };
        ReflectionTestUtils.setField(manager,"jdbc",jdbc); ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(manager,"json",new ObjectMapper()); ReflectionTestUtils.setField(manager,"recommendations",recommendations);
        ReflectionTestUtils.setField(manager,"candidates",candidates); ReflectionTestUtils.setField(manager,"access",access);
        List<Map<String,Object>> items = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            LearningCandidateStore.Candidate c = new LearningCandidateStore.Candidate();
            c.id = i == 1 ? "PROGRAMMING:1" : "OBJECTIVE:2"; c.key = i == 1 ? "local:1" : "objective:2";
            c.kind = i == 1 ? "PROGRAMMING" : "OBJECTIVE"; c.source = "HOJ"; c.title = "Public task " + i; c.displayId = "P" + i; c.url = "/problem/P" + i; c.questionId = i == 1 ? null : 2L;
            visible.put(c.id,c); Map<String,Object> item = new LinkedHashMap<>(); item.put("id",c.id); item.put("title",c.title); item.put("reasons",Collections.singletonList("保存时知识方向")); items.add(item);
        }
        verified.put("items",items); verified.put("filters",Collections.singletonMap("source","HOJ")); verified.put("basis",Collections.singletonMap("historyComplete",false)); verified.put("fingerprint",String.join("",Collections.nCopies(64,"a"))); verified.put("version","test-v1");
        when(recommendations.verify(anyString(),any())).thenAnswer(invocation -> verified);
        when(candidates.find(anyString(),any())).thenAnswer(invocation -> new LinkedHashMap<>(visible));
        when(candidates.attempted(anyString())).thenAnswer(invocation -> new HashSet<>(attempted)); when(candidates.solved(anyString())).thenAnswer(invocation -> new HashSet<>(solved));
        when(candidates.identities(any())).thenAnswer(invocation -> { Map<String,String> result=new HashMap<>(); for(String key : (Collection<String>)invocation.getArgument(0)) result.put(key,key.equals("cf:contest:4:A") ? "local:1" : key); return result; });
    }
    TrainingPlanDTO.Create create(String nonce) {
        TrainingPlanDTO.Create dto = new TrainingPlanDTO.Create(); dto.setName("我的下一组"); dto.setRequestId(nonce);
        AiDTO.RecommendationExplanation selection = new AiDTO.RecommendationExplanation(); AiDTO.RecommendationFilter filters = new AiDTO.RecommendationFilter(); filters.setSource("HOJ");
        selection.setFilters(filters); selection.setFingerprint((String) verified.get("fingerprint")); selection.setCandidateIds(Arrays.asList("PROGRAMMING:1","OBJECTIVE:2")); dto.setSelection(selection); return dto;
    }
    TrainingPlanDTO.Update update(Map<String,Object> row, String state) { TrainingPlanDTO.Update dto = new TrainingPlanDTO.Update(); dto.setState(state); dto.setExpectedRevision(((Number)row.get("revision")).longValue()); return dto; }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM learning_training_plan",Integer.class); }
}
