package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.exception.StatusFailException;
import top.hcode.hoj.dao.problem.TagEntityService;
import top.hcode.hoj.dao.problem.TagClassificationEntityService;
import top.hcode.hoj.dao.training.TrainingCategoryEntityService;
import top.hcode.hoj.manager.admin.tag.AdminTagManager;
import top.hcode.hoj.manager.admin.training.AdminTrainingCategoryManager;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import top.hcode.hoj.pojo.entity.problem.Tag;
import top.hcode.hoj.pojo.entity.problem.TagClassification;
import top.hcode.hoj.pojo.entity.training.TrainingCategory;

import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningProfileManagerTest {
    JdbcTemplate jdbc; ObjectMapper json; AlgorithmCatalog catalog; LearningProfileManager manager;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:learning_profile;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source); jdbc.execute("DROP ALL OBJECTS"); json = new ObjectMapper();
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32),username VARCHAR(32),status INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,rating INT,auth INT,is_group BOOLEAN DEFAULT FALSE,author VARCHAR(32),problem_id VARCHAR(100),is_remote BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT PRIMARY KEY,uid VARCHAR(32),pid BIGINT,cid BIGINT DEFAULT 0,gid BIGINT,paper_run_id BIGINT,status INT,judger VARCHAR(20))");
        jdbc.execute("CREATE TABLE tag(id BIGINT PRIMARY KEY,name VARCHAR(100),gid BIGINT)");
        jdbc.execute("CREATE TABLE problem_tag(pid BIGINT,tid BIGINT)");
        jdbc.execute("CREATE TABLE algorithm_topic(code VARCHAR(80),problem_tag_id BIGINT,training_category_id BIGINT,tag_classification_id BIGINT)");
        try (Connection connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("objective.sql")); }
        jdbc.update("INSERT INTO user_info VALUES('a','alice',0),('b','bob',1)");
        jdbc.update("INSERT INTO tag VALUES(11,'dp',NULL),(12,'数学',NULL),(13,'未来自定义方向',NULL),(14,'图论',1)");
        jdbc.update("INSERT INTO algorithm_topic VALUES('dp.general',11,21,31)");
        catalog = new AlgorithmCatalog(); ReflectionTestUtils.setField(catalog,"jdbc",jdbc);
        manager = new LearningProfileManager() { @Override protected long now() { return 1000; } };
        ReflectionTestUtils.setField(manager,"jdbc",jdbc); ReflectionTestUtils.setField(manager,"catalog",catalog);
        ReflectionTestUtils.setField(manager,"json",json); ReflectionTestUtils.setField(manager,"objectives",new ObjectiveManager());
    }
    @Test void catalogueHasStableCodesAndExplicitAliasesAndKeepsCustomVocabularySeparate() {
        assertEquals(94,catalog.topics().size()); assertEquals(8,catalog.groups().size());
        assertEquals("dp",catalog.topicForTag("  DP  ").getGroupCode());
        assertEquals("graph",catalog.topicForTag("graphs").getGroupCode());
        assertNull(catalog.topicForTag("未来自定义方向")); assertNull(catalog.topicForTag("probably dynamic programming"));
        assertEquals(94,((List<?>)catalog.view().get("topics")).size());
        assertEquals(94,catalog.topics().stream().map(AlgorithmCatalog.Topic::getCode).distinct().count());
    }
    @Test void programmingEvidenceDeduplicatesPublicPracticeAndComputesKnownRatedQuartile() {
        for (int id=1; id<=4; id++) { program(id, id*400, 1, false, "coach"); judge(id,id,0,null,null,0); }
        judge(5,1,0,null,null,0); judge(6,1,0,null,null,-1);
        program(5,null,1,false,"coach"); judge(7,5,0,null,null,0);
        Map<String,Object> result=manager.profile("a"), overall=overall(result), dp=axis(result,"dp");
        assertEquals(5L,overall.get("solved")); assertEquals(4,overall.get("ratedSolved"));
        assertEquals(1200,dp.get("representativeRating")); assertEquals("LIMITED",dp.get("confidence"));
        assertEquals(1L,overall.get("unratedProgrammingSolved"));
        assertNull(axis(result,"geometry").get("representativeRating"));
    }
    @Test void hiddenAuthoredGroupContestPaperPendingAndOtherUsersDoNotInflatePublicProfile() {
        for (int id=1; id<=8; id++) { program(id,1400,id==2?2:1,id==3,id==4?"alice":"coach"); judge(id,id,id==5?7:0,id==6?9L:null,id==7?3L:null,id==8?5:0); }
        jdbc.update("UPDATE judge SET uid='someone_else' WHERE submit_id=1");
        assertEquals(0L,overall(manager.profile("a")).get("attempted"));
        program(9,1400,1,false,"coach"); judge(9,9,0,null,null,-1);
        assertEquals(1L,overall(manager.profile("a")).get("attempted"));
        assertEquals(0L,overall(manager.profile("a")).get("solved"));
    }
    @Test void mirroredCodeforcesReceiptDoesNotCountAsAnHojSolve() {
        program(1,800,1,false,"coach"); judge(1,1,0,null,null,0);
        jdbc.update("UPDATE problem SET is_remote=TRUE,problem_id='CF-4A' WHERE id=1");
        jdbc.update("UPDATE judge SET judger='CodeforcesSync' WHERE submit_id=1");
        assertEquals(0L, overall(manager.profile("a")).get("solved"));
        assertTrue(manager.publicProgrammingEvidence("a").isEmpty());
    }
    @Test void objectiveEvidenceUsesFrozenAnswersAndCountsConceptsSeparatelyWithoutLeakingAnswers() throws Exception {
        ObjectiveDTO.Question frozen=question(1,"AFTER_ANSWER"); objective(frozen,"coach",true,true);
        run(1,frozen,"B",null,"PRACTICE"); run(2,frozen,"A",null,"PRACTICE"); run(3,frozen,"A",null,"PRACTICE");
        ObjectiveDTO.Question current=question(1,"AFTER_ANSWER"); current.setCorrectAnswers(Collections.singletonList("B"));
        jdbc.update("UPDATE objective_question SET content_json=? WHERE id=1",json.writeValueAsString(current));
        program(1,1200,1,false,"coach"); judge(1,1,0,null,null,0);
        Map<String,Object> result=manager.profile("a"), overall=overall(result);
        assertEquals(1L,overall.get("solved")); assertEquals(1L,overall.get("objectiveSolved"));
        assertEquals(1L,overall.get("objectiveAttempted")); assertEquals(1,overall.get("ratedSolved"));
        assertEquals(1L,axis(result,"math").get("objectiveSolved"));
        assertEquals(Collections.singletonMap(1L,true),manager.publicObjectiveEvidence("a"));
        String response=json.writeValueAsString(result); assertFalse(response.contains("correctAnswers")); assertFalse(response.contains("explanation"));
    }
    @Test void objectiveFeedbackPolicyAndVisibilityCannotBeBypassedThroughTheProfile() throws Exception {
        for (int id=1; id<=7; id++) {
            ObjectiveDTO.Question question=question(id,id==1?"NEVER":id==2?"AT_TIME":"AFTER_ANSWER"); question.setAnswerReleaseAt(2000L);
            objective(question,id==3?"a":"coach",id!=4,id!=5);
            run(id,question,"A",id==6?1L:null,id==7?"PAPER":"PRACTICE");
        }
        assertEquals(0L,overall(manager.profile("a")).get("objectiveAttempted"));
        ObjectiveDTO.Question released=question(2,"AT_TIME"); released.setAnswerReleaseAt(999L);
        jdbc.update("UPDATE objective_question SET content_json=? WHERE id=2",json.writeValueAsString(released));
        assertEquals(1L,overall(manager.profile("a")).get("objectiveSolved"));
        assertEquals(Collections.singletonMap(2L,true),manager.publicObjectiveEvidence("a"));
    }
    @Test void missingTagsAndRatingsRemainExplicitAndUnavailableProfilesReturnNotFound() {
        program(1,null,1,false,"coach"); judge(1,1,0,null,null,0); jdbc.update("DELETE FROM problem_tag");
        Map<String,Object> overall=overall(manager.profile("a")); assertEquals(1L,overall.get("unmappedSolved"));
        assertNull(overall.get("representativeRating")); assertEquals("INSUFFICIENT",overall.get("confidence"));
        assertThrows(ObjectiveException.class,()->manager.profile("unknown")); assertThrows(ObjectiveException.class,()->manager.profile("b"));
    }
    @Test void everyAdministrativeMutationProtectsBuiltinsAndCustomUpdatesStillWork() throws Exception {
        AdminTagManager tags=new AdminTagManager(); TagEntityService tagService=mock(TagEntityService.class); TagClassificationEntityService classificationService=mock(TagClassificationEntityService.class);
        ReflectionTestUtils.setField(tags,"algorithmCatalog",catalog); ReflectionTestUtils.setField(tags,"tagEntityService",tagService); ReflectionTestUtils.setField(tags,"tagClassificationEntityService",classificationService);
        AdminTrainingCategoryManager training=new AdminTrainingCategoryManager(); TrainingCategoryEntityService categoryService=mock(TrainingCategoryEntityService.class);
        ReflectionTestUtils.setField(training,"algorithmCatalog",catalog); ReflectionTestUtils.setField(training,"trainingCategoryEntityService",categoryService);
        Tag tag=new Tag(); tag.setId(11L); TagClassification classification=new TagClassification(); classification.setId(31L); TrainingCategory category=new TrainingCategory(); category.setId(21L);
        assertThrows(StatusFailException.class,()->tags.updateTag(tag)); assertThrows(StatusFailException.class,()->tags.deleteTag(11L));
        assertThrows(StatusFailException.class,()->tags.updateTagClassification(classification)); assertThrows(StatusFailException.class,()->tags.deleteTagClassification(31L));
        assertThrows(StatusFailException.class,()->training.updateTrainingCategory(category)); assertThrows(StatusFailException.class,()->training.deleteTrainingCategory(21L));
        verifyNoInteractions(tagService,classificationService,categoryService);
        tag.setId(99L); classification.setId(99L); category.setId(99L);
        when(tagService.updateById(tag)).thenReturn(true); when(classificationService.updateById(classification)).thenReturn(true); when(categoryService.updateById(category)).thenReturn(true);
        tags.updateTag(tag); tags.updateTagClassification(classification); training.updateTrainingCategory(category);
        verify(tagService).updateById(tag); verify(classificationService).updateById(classification); verify(categoryService).updateById(category);
    }
    @Test void programmingDetailsUseTheSameEligibilityAndOnlySettledTimesAndCounts() {
        jdbc.execute("ALTER TABLE problem ADD title VARCHAR(255)");jdbc.execute("ALTER TABLE judge ADD submit_time TIMESTAMP");
        // Insert explicit columns: the older fixtures intentionally have no timestamp field.
        for(int id=1;id<=8;id++) {
            program(id,1400,id==2?2:1,id==3,id==4?"alice":"coach");
            jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,gid,paper_run_id,status,submit_time) VALUES(?,'a',?,?,?,?,?,?)",id,id,id==5?7:0,id==6?9L:null,id==7?3L:null,id==8?5:0,new java.sql.Timestamp(id*1000));
        }
        jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,status,submit_time) VALUES(9,'a',1,0,-1,?)",new java.sql.Timestamp(500));
        jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,status,submit_time) VALUES(10,'a',1,0,5,?)",new java.sql.Timestamp(20000));
        jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,status,submit_time) VALUES(11,'someone_else',1,0,0,?)",new java.sql.Timestamp(100));
        List<Map<String,Object>> details=manager.publicProgrammingDetails("a");assertEquals(1,details.size());assertEquals(1,manager.publicProgrammingEvidence("a").size());Map<String,Object> row=details.get(0);
        assertEquals(2L,((Number)row.get("submissions")).longValue());assertEquals(500,((java.util.Date)row.get("first_at")).getTime());assertEquals(1000,((java.util.Date)row.get("last_at")).getTime());assertEquals(1000,((java.util.Date)row.get("first_solved_at")).getTime());
    }
    void program(long id,Integer rating,int auth,boolean group,String author) {
        jdbc.update("INSERT INTO problem(id,rating,auth,is_group,author) VALUES(?,?,?,?,?)",id,rating,auth,group,author); jdbc.update("INSERT INTO problem_tag VALUES(?,11)",id);
    }
    void judge(long id,long pid,long cid,Long gid,Long paper,int status) { jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,gid,paper_run_id,status) VALUES(?,'a',?,?,?,?,?)",id,pid,cid,gid,paper,status); }
    ObjectiveDTO.Question question(long id,String policy) {
        ObjectiveDTO.Question q=new ObjectiveDTO.Question(); q.setId(id); q.setQuestionType("SINGLE_CHOICE"); q.setTitle("choice"); q.setCorrectAnswers(Collections.singletonList("A")); q.setTagIds(Collections.singletonList(12L)); q.setAnswerPolicy(policy); return q;
    }
    void objective(ObjectiveDTO.Question q,String author,boolean published,boolean practice) throws Exception {
        jdbc.update("INSERT INTO objective_question(id,title,question_type,version,published,practice_enabled,created_by,content_json,gmt_create,gmt_modified) VALUES(?,'choice','SINGLE_CHOICE',1,?,?,?, ?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",q.getId(),published,practice,author,json.writeValueAsString(q));
    }
    void run(long id,ObjectiveDTO.Question q,String answer,Long set,String mode) throws Exception {
        jdbc.update("INSERT INTO objective_run(id,question_id,set_id,uid,request_id,mode,title,state,snapshot_json,answers_json,gmt_create,gmt_modified) VALUES(?,?,?,'a',?,?,'choice','FINISHED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,q.getId(),set,"r"+id,mode,json.writeValueAsString(Collections.singletonList(q)),json.writeValueAsString(Collections.singletonMap(q.getId().toString(),Collections.singletonList(answer))));
    }
    @SuppressWarnings("unchecked") Map<String,Object> overall(Map<String,Object> result) { return (Map<String,Object>)result.get("overall"); }
    @SuppressWarnings("unchecked") Map<String,Object> axis(Map<String,Object> result,String code) { return ((List<Map<String,Object>>)result.get("axes")).stream().filter(axis->code.equals(axis.get("code"))).findFirst().get(); }
}
