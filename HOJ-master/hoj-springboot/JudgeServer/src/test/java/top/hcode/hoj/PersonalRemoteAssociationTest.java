package top.hcode.hoj;

import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import top.hcode.hoj.dao.UserAcproblemEntityService;
import top.hcode.hoj.dao.impl.ContestRecordEntityServiceImpl;
import top.hcode.hoj.judge.JudgeContext;
import top.hcode.hoj.mapper.ContestRecordMapper;
import top.hcode.hoj.remoteJudge.personal.PersonalRemoteWorker;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real DAO and JudgeContext below the personal worker. */
class PersonalRemoteAssociationTest {
    int judgeUpdates=1;
    JdbcTemplate jdbc;ContestRecordMapper mapper;ContestRecordEntityServiceImpl records;
    UserAcproblemEntityService accepted;JudgeContext context;PersonalRemoteWorker worker;Map<String,Object> judge;
    @BeforeEach void setup(){
        jdbc=mock(JdbcTemplate.class,call->"update".equals(call.getMethod().getName())?judgeUpdates:RETURNS_DEFAULTS.answer(call));mapper=mock(ContestRecordMapper.class);records=new ContestRecordEntityServiceImpl();
        ReflectionTestUtils.setField(records,"baseMapper",mapper);ApplicationContext application=mock(ApplicationContext.class);
        when(application.getBean(ContestRecordEntityServiceImpl.class)).thenReturn(records);ReflectionTestUtils.setField(records,"applicationContext",application);
        accepted=mock(UserAcproblemEntityService.class);context=new JudgeContext();
        ReflectionTestUtils.setField(context,"contestRecordEntityService",records);ReflectionTestUtils.setField(context,"userAcproblemEntityService",accepted);
        judge=new HashMap<>();judge.put("uid","alice");judge.put("pid",1L);judge.put("cid",1L);judge.put("gid",null);
        when(jdbc.queryForMap("SELECT uid,pid,cid,gid FROM judge WHERE submit_id=?",42L)).thenReturn(judge);
        worker=new PersonalRemoteWorker(jdbc,mock(PlatformTransactionManager.class),mock(StringRedisTemplate.class),context,"unused-local-test-key-directory");
    }
    void apply(){ReflectionTestUtils.invokeMethod(worker,"applyResult",42L,new PersonalRemoteWorkerTest().sourceResult("OK"),0);}
    @Test void missingContestAssociationCannotBeSilentlyCompleted(){
        when(mapper.update(isNull(),any())).thenReturn(0);
        RuntimeException failure=assertThrows(RuntimeException.class,this::apply);
        assertTrue(failure.getMessage().contains("contest_record"));verify(mapper,times(1)).update(isNull(),any());
    }
    @Test void contestSqlFailurePropagatesThroughTheRealContextExactlyOnce(){
        DataAccessResourceFailureException failure=new DataAccessResourceFailureException("synthetic association write");
        when(mapper.update(isNull(),any())).thenThrow(failure);
        assertSame(failure,assertThrows(RuntimeException.class,this::apply));verify(mapper,times(1)).update(isNull(),any());
    }
    @Test void unsuccessfulPracticeAcSaveIsAlsoAWriteFailure(){
        judge.put("cid",0L);when(accepted.saveOrUpdate(any())).thenReturn(false);
        assertThrows(RuntimeException.class,this::apply);verify(accepted,times(1)).saveOrUpdate(any());verifyNoInteractions(mapper);
    }
    @Test void missingJudgeCannotCreateAnAssociation(){
        judgeUpdates=0;when(mapper.update(isNull(),any())).thenReturn(1);
        assertThrows(RuntimeException.class,this::apply);verifyNoInteractions(mapper,accepted);
    }
    @Test void ordinaryJudgeEntryKeepsItsExistingExceptionHandling(){
        when(mapper.update(isNull(),any())).thenThrow(new DataAccessResourceFailureException("synthetic old path"));
        assertDoesNotThrow(()->records.updateContestRecord(100,0,42L,12));verify(mapper,times(1)).update(isNull(),any());
    }
    @Test void successfulContestAndTeamPracticeKeepExistingRouting(){
        when(mapper.update(isNull(),any())).thenReturn(1);apply();verify(mapper,times(1)).update(isNull(),any());
        judge.put("cid",0L);judge.put("gid",7L);apply();verifyNoInteractions(accepted);verifyNoMoreInteractions(mapper);
    }
}
