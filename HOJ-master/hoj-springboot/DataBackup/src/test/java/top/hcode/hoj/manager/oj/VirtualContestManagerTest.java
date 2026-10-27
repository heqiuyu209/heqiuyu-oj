package top.hcode.hoj.manager.oj;

import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.dao.contest.*;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.VirtualContestDTO;
import top.hcode.hoj.pojo.entity.contest.*;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.*;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VirtualContestManagerTest {
    JdbcTemplate jdbc; TransactionTemplate tx; VirtualContestManager manager; AiAccessGuard ai; PaperManager papers;
    ContestRegisterEntityService registrations; GroupValidator groups;
    long time; String uid; boolean root;
    @BeforeEach void setup() throws Exception {
        time=1000000;uid="alice";root=false;
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:virtual_contest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("DROP ALL OBJECTS");tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0),('disabled',1)");
        jdbc.execute("CREATE TABLE contest(id BIGINT PRIMARY KEY,start_time TIMESTAMP,end_time TIMESTAMP,auth INT DEFAULT 0,visible BOOLEAN DEFAULT TRUE,uid VARCHAR(32) DEFAULT 'teacher',title VARCHAR(500),is_group BOOLEAN DEFAULT FALSE,gid BIGINT,open_account_limit BOOLEAN DEFAULT FALSE,account_limit_rule TEXT)");
        jdbc.execute("CREATE TABLE contest_register(cid BIGINT,uid VARCHAR(32),status INT)");jdbc.execute("CREATE TABLE ai_contest_entry(cid BIGINT,uid VARCHAR(32))");
        jdbc.execute("CREATE TABLE judge(cid BIGINT,uid VARCHAR(32),submit_time TIMESTAMP)");jdbc.execute("CREATE TABLE paper_run(id BIGINT AUTO_INCREMENT PRIMARY KEY,contest_id BIGINT,uid VARCHAR(32),virtual_run_id BIGINT)");
        jdbc.execute("CREATE TABLE contest_paper(contest_id BIGINT)");jdbc.execute("CREATE TABLE contest_problem(cid BIGINT)");
        String schema=StreamUtils.copyToString(new ClassPathResource("virtual-contests.sql").getInputStream(),java.nio.charset.StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","");
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        for(long id=1;id<=3;id++){
            jdbc.update("INSERT INTO contest(id,start_time,end_time,title) VALUES(?,?,?,?)",id,new Timestamp(0),new Timestamp(60000),"practice "+id);jdbc.update("INSERT INTO contest_problem VALUES(?)",id);
        }
        registrations=mock(ContestRegisterEntityService.class);groups=mock(GroupValidator.class);
        ContestValidator validator=new ContestValidator();ReflectionTestUtils.setField(validator,"contestRegisterEntityService",registrations);ReflectionTestUtils.setField(validator,"groupValidator",groups);
        ai=new AiAccessGuard(){@Override protected long now(){return time;}};ReflectionTestUtils.setField(ai,"jdbc",jdbc);
        manager=new VirtualContestManager(){@Override protected long now(){return time;}@Override protected AccountProfile actor(){if(uid==null)return null;AccountProfile p=new AccountProfile();p.setUid(uid);p.setUsername(uid);return p;}@Override protected boolean root(){return root;}};
        ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"validator",validator);ReflectionTestUtils.setField(manager,"ai",ai);
        papers=mock(PaperManager.class);ReflectionTestUtils.setField(manager,"papers",papers);
    }
    VirtualContestDTO request(String nonce){VirtualContestDTO dto=new VirtualContestDTO();dto.setRequestId(nonce);return dto;}
    @SuppressWarnings("unchecked") Map<String,Object> start(long cid,String nonce){return (Map<String,Object>)tx.execute(s->manager.start(cid,request(nonce))).get("run");}
    void denied(int status,Runnable action){assertEquals(status,assertThrows(ObjectiveException.class,action::run).getStatus());}
    @Test void fixedPersonalClockAndIdempotenceSurviveReloadsWithoutChangingFormalMembership(){
        Map<String,Object> first=start(1,"request_1");assertEquals(time,first.get("startedAt"));assertEquals(time+60000,first.get("endsAt"));assertTrue(ai.contestBlocked("alice"));assertFalse(ai.contestBlocked("bob"));
        time+=1000;assertEquals(first.get("id"),start(1,"request_1").get("id"));assertEquals(first.get("endsAt"),start(1,"another_nonce").get("endsAt"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM contest_register",Integer.class));
        uid="bob";assertNull(manager.current(1L).get("run"));start(1,"request_1");assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
    }
    @Test void rejectsAnotherActiveContestAndNonceReuseButAllowsAnExplicitNewRoundAfterDeadline(){
        Map<String,Object> first=start(1,"request_1");denied(409,()->start(2,"request_1"));denied(409,()->start(2,"request_2"));
        time+=60000;assertFalse(ai.contestBlocked("alice"));assertEquals("ENDED",start(1,"request_1").get("state"));assertEquals(first.get("id"),start(1,"request_1").get("id"));
        Map<String,Object> next=start(2,"request_2");assertNotEquals(first.get("id"),next.get("id"));assertEquals(time+60000,next.get("endsAt"));
    }
    @Test void currentAccessChecksPrivateProtectedGroupAndHiddenContestRules(){
        jdbc.update("UPDATE contest SET auth=1 WHERE id=1");denied(403,()->start(1,"request_1"));
        jdbc.update("UPDATE contest SET auth=2 WHERE id=1");denied(403,()->start(1,"request_1"));
        when(registrations.getOne(any(),eq(false))).thenReturn(new ContestRegister());assertNotNull(start(1,"request_1"));
        jdbc.update("UPDATE contest SET visible=FALSE WHERE id=1");denied(403,()->manager.current(1L));
        jdbc.update("UPDATE contest SET is_group=TRUE,gid=9 WHERE id=2");denied(403,()->manager.current(2L));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
    }
    @Test void invalidRunningMixedEmptyAndDisabledStartsCreateNoWindow(){
        denied(400,()->tx.execute(s->manager.start(1L,request("bad"))));
        jdbc.update("UPDATE contest SET end_time=? WHERE id=1",new Timestamp(time+1));denied(400,()->start(1,"request_1"));jdbc.update("UPDATE contest SET end_time=? WHERE id=1",new Timestamp(0));denied(400,()->start(1,"request_1"));
        jdbc.update("DELETE FROM contest_problem WHERE cid=3");denied(400,()->start(3,"request_3"));
        uid="disabled";denied(403,()->start(1,"request_1"));uid=null;denied(401,()->manager.current(1L));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
    }
    @Test void formalParticipationPreventsStartingASeparateVirtualClockAndAdminDoesNotBypassAi(){
        jdbc.update("UPDATE contest SET end_time=? WHERE id=3",new Timestamp(time+60000));jdbc.update("INSERT INTO contest_register VALUES(3,'alice',0)");root=true;
        denied(403,()->start(1,"request_1"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
    }
    @Test void changesToOriginalClockOrVisibilityDoNotShortenExistingAiRestriction(){
        Map<String,Object> first=start(1,"request_1");jdbc.update("UPDATE contest SET visible=FALSE,end_time=? WHERE id=1",new Timestamp(1));
        assertTrue(ai.contestBlocked("alice"));time=((Number)first.get("endsAt")).longValue()-1;assertTrue(ai.contestBlocked("alice"));time++;assertFalse(ai.contestBlocked("alice"));
    }
    @Test void aMixedWindowAndItsAnswerSheetCommitTogetherOrRollbackTogether(){
        jdbc.update("INSERT INTO contest_paper VALUES(2)");doThrow(new ObjectiveException(400,"冻结失败")).when(papers).startVirtual(anyLong());
        denied(400,()->start(2,"request_2"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
        reset(papers);when(papers.startVirtual(anyLong())).thenAnswer(call->{jdbc.update("INSERT INTO paper_run(uid,virtual_run_id) VALUES('alice',?)",(Long)call.getArgument(0));return Collections.emptyMap();});
        Map<String,Object> result=start(2,"request_2");assertEquals("MIXED",result.get("kind"));assertNotNull(result.get("paperRunId"));
        assertEquals(result.get("id"),start(2,"new_request").get("id"));verify(papers,times(1)).startVirtual(anyLong());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_run",Integer.class));
    }
}
