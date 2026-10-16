package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.dao.contest.ContestEntityService;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.pojo.vo.ACMContestRankVO;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SiteRatingManagerTest {
    JdbcTemplate jdbc; ObjectMapper json; SiteRatingManager manager;
    ContestEntityService contests; ContestCalculateRankManager ranks;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:site_rating;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS"); json = new ObjectMapper();
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,title VARCHAR(255),author VARCHAR(32),auth INT,is_group BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE contest(id BIGINT PRIMARY KEY,visible BOOLEAN DEFAULT TRUE,is_group BOOLEAN DEFAULT FALSE,gid BIGINT,end_time TIMESTAMP)");
        try (Connection c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new ClassPathResource("site-rating.sql")); ScriptUtils.executeSqlScript(c, new ClassPathResource("objective.sql")); }
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32),username VARCHAR(32),nickname VARCHAR(32),avatar VARCHAR(255),status INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE role(id BIGINT,role VARCHAR(32))"); jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id BIGINT)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT PRIMARY KEY,uid VARCHAR(32),pid BIGINT,cid BIGINT DEFAULT 0,gid BIGINT,paper_run_id BIGINT,submit_time TIMESTAMP,status INT)");
        jdbc.execute("CREATE TABLE paper_run(id BIGINT,contest_id BIGINT,state VARCHAR(16))");
        jdbc.execute("CREATE TABLE contest_paper(contest_id BIGINT)");
        jdbc.update("INSERT INTO user_info(uuid,username) VALUES('a','alice'),('b','bob'),('root','root')");
        jdbc.update("INSERT INTO role VALUES(1,'root')"); jdbc.update("INSERT INTO user_role VALUES('root',1)");
        jdbc.update("INSERT INTO problem(id,title,author,auth,rating) VALUES(1,'rated task','coach',1,1200),(2,'unrated','coach',1,NULL),(3,'hidden','coach',2,1600),(4,'own task','alice',1,1600)");
        manager = new SiteRatingManager(); contests = mock(ContestEntityService.class); ranks = mock(ContestCalculateRankManager.class);
        set("jdbc", jdbc); set("json", json); set("objectives", new ObjectiveManager()); set("contests", contests); set("ranks", ranks); set("mixedRanks", mock(MixedContestRankManager.class));
    }
    @Test void onlyUniqueIndependentPublicRatedPracticePassesCountAndReplayIsIdempotent() throws Exception {
        for (int id = 1; id <= 4; id++) judge(id, "a", id, 0, null, 0);
        judge(5,"a",1,0,null,0); judge(6,"b",1,1,null,0); judge(7,"b",1,0,1L,0); judge(8,"root",1,0,null,0);
        jdbc.update("UPDATE judge SET gid=1 WHERE submit_id=4");
        manager.rebuild(); int rating = number(profile("a", "practice").get("rating"));
        assertEquals(1, number(profile("a", "practice").get("rated_count"))); assertTrue(rating > 800);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM site_rating_history", Integer.class));
        manager.rebuild(); assertEquals(rating, number(profile("a", "practice").get("rating")));
        jdbc.update("UPDATE judge SET status=1 WHERE pid=1"); manager.rebuild();
        assertTrue(jdbc.queryForList("SELECT * FROM site_rating_profile").isEmpty());
    }
    @Test void objectiveSnapshotCorrectnessAndOneCountPerTaskAreIndependentFromPapers() throws Exception {
        ObjectiveDTO.Question q = new ObjectiveDTO.Question(); q.setId(1L); q.setTitle("choice"); q.setQuestionType("SINGLE_CHOICE"); q.setRating(1000); q.setCorrectAnswers(Collections.singletonList("A"));
        jdbc.update("INSERT INTO objective_question(id,title,question_type,version,published,practice_enabled,created_by,content_json,gmt_create,gmt_modified) VALUES(1,'choice','SINGLE_CHOICE',1,1,1,'coach',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", json.writeValueAsString(q));
        for (int id=1; id<=4; id++) jdbc.update("INSERT INTO objective_run(id,question_id,set_id,uid,request_id,mode,title,state,snapshot_json,answers_json,gmt_create,gmt_modified) VALUES(?,1,?,'a',? ,?,'choice','FINISHED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, id==4?1:null, "r"+id, id==3?"PAPER":"PRACTICE", json.writeValueAsString(Collections.singletonList(q)), id==1?"{\"1\":[\"B\"]}":"{\"1\":[\"A\"]}");
        manager.rebuild(); assertEquals(1, number(profile("a", "practice").get("rated_count")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM site_rating_history", Integer.class));
    }
    @Test void chronologicalContestsUsePriorRatingAndRejudgesRepairLaterHistory() throws Exception {
        contest(1); contest(2);
        when(ranks.calcACMRank(eq(false), eq(true), any(), isNull(), isNull(), isNull(), eq(false))).thenReturn(Arrays.asList(row("a",1),row("b",2)));
        manager.rebuild(); assertEquals(2, number(profile("a", "contest").get("rated_count")));
        List<Map<String,Object>> history=jdbc.queryForList("SELECT * FROM site_rating_history WHERE uid='a' ORDER BY occurred_at");
        assertEquals(history.get(0).get("rating"),history.get(1).get("old_rating"));
        int original=number(profile("a","contest").get("rating"));
        when(ranks.calcACMRank(eq(false), eq(true), any(), isNull(), isNull(), isNull(), eq(false))).thenReturn(Arrays.asList(row("b",1),row("a",2)));
        manager.rebuild(); assertTrue(number(profile("a","contest").get("rating"))<original);
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM site_rating_history",Integer.class));
    }
    @Test void unfinishedJudgingBlocksLaterSettlementAndSingleParticipantIsUnrated() throws Exception {
        contest(1); contest(2); judge(1,"a",1,1,null,5);
        when(ranks.calcACMRank(eq(false),eq(true),any(),isNull(),isNull(),isNull(),eq(false))).thenReturn(Collections.singletonList(row("a",1)));
        manager.rebuild(); assertTrue(jdbc.queryForList("SELECT * FROM site_rating_profile").isEmpty()); verifyNoInteractions(ranks);
        jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1"); manager.rebuild();
        assertTrue(jdbc.queryForList("SELECT * FROM site_rating_profile").isEmpty());
    }
    @Test void tiesSearchAndPaginationPreserveGlobalRank() throws Exception {
        judge(1,"a",1,0,null,0); judge(2,"b",1,0,null,0); manager.rebuild();
        Map<String,Object> rank=manager.rank("practice",1,1,"bob");
        List<?> records=(List<?>)rank.get("records"); assertEquals(1,records.size());
        assertEquals(1, number(((Map<?,?>)records.get(0)).get("rank")));
        assertEquals(1L,rank.get("total"));
        assertNull(manager.profile("unknown").get("contestRating"));
    }
    @Test void typedHistoryKeepsOlderContestsWhenMoreThanOneHundredPracticeEventsExist() {
        jdbc.update("INSERT INTO site_rating_history(uid,type,source_key,title,occurred_at,old_rating,rating,delta,place) VALUES('a','contest','CONTEST:1','older round',?,1400,1496,96,1)", new Date(1000));
        for (int index = 0; index < 105; index++) jdbc.update("INSERT INTO site_rating_history(uid,type,source_key,title,occurred_at,old_rating,rating,delta) VALUES('a','practice',?,'practice',?,800,829,29)", "PROGRAMMING:"+index, new Date(2000+index));
        List<?> contestHistory = (List<?>) manager.profile("a", "contest").get("history");
        assertEquals(1, contestHistory.size());
        assertEquals("CONTEST:1", ((Map<?,?>)contestHistory.get(0)).get("sourceKey"));
        List<?> practiceHistory = (List<?>) manager.profile("a", "practice").get("history");
        assertEquals(100, practiceHistory.size());
        assertTrue(practiceHistory.stream().allMatch(item -> "practice".equals(((Map<?,?>)item).get("type"))));
        assertEquals(100, ((List<?>)manager.profile("a").get("history")).size());
        assertTrue(((List<?>)manager.profile("unknown", "contest").get("history")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> manager.profile("a", "invalid"));
    }
    void contest(long id) {
        Date end=new Date(100000+id*1000); jdbc.update("INSERT INTO contest(id,rated,end_time) VALUES(?,1,?)",id,end);
        Contest contest=new Contest().setId(id).setType(0).setUid("coach").setTitle("round "+id).setStartTime(new Date(1000)).setEndTime(end).setSealRank(false);
        when(contests.getById(id)).thenReturn(contest);
    }
    ACMContestRankVO row(String uid,int rank) { return new ACMContestRankVO().setUid(uid).setRank(rank); }
    void judge(long id,String uid,long pid,long cid,Long paper,int status) { jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,paper_run_id,status,submit_time) VALUES(?,?,?,?,?,?,?)",id,uid,pid,cid,paper,status,new Date(2000+id*1000)); }
    Map<String,Object> profile(String uid,String type) { return jdbc.queryForMap("SELECT * FROM site_rating_profile WHERE uid=? AND type=?",uid,type); }
    int number(Object value) { return ((Number)value).intValue(); }
    void set(String field,Object value) { ReflectionTestUtils.setField(manager,field,value); }
}
