package top.hcode.hoj.manager.oj;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.dao.judge.JudgeEntityService;
import top.hcode.hoj.judge.self.JudgeDispatcher;
import top.hcode.hoj.mapper.*;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.PaperProgramSnapshot;
import top.hcode.hoj.pojo.vo.SubmissionStatisticsVO;
import top.hcode.hoj.schedule.ScheduleServiceImpl;
import top.hcode.hoj.service.admin.rejudge.RejudgeService;
import top.hcode.hoj.utils.RedisUtils;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real mapper/transaction checks for crash recovery and ordinary-statistics isolation. */
class PaperPendingRecoveryTest {
    PaperProgramManager manager;
    JdbcTemplate jdbc;
    JudgeMapper judges;
    JudgeDispatcher dispatcher;
    PlatformTransactionManager transactions;
    ProblemMapper problems;

    @BeforeEach void setup() throws Exception {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:paper_recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
        MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
        SqlSessionFactory sessions = factory.getObject(); sessions.getConfiguration().addMapper(JudgeMapper.class); sessions.getConfiguration().addMapper(PaperProgramSnapshotMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sessions); judges = template.getMapper(JudgeMapper.class);
        PaperProgramManager target = new PaperProgramManager(); dispatcher = mock(JudgeDispatcher.class); problems = mock(ProblemMapper.class);
        ReflectionTestUtils.setField(target, "judgeMapper", judges); ReflectionTestUtils.setField(target, "snapshotMapper", template.getMapper(PaperProgramSnapshotMapper.class));
        ReflectionTestUtils.setField(target, "dispatcher", dispatcher); ReflectionTestUtils.setField(target, "problemMapper", problems);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        manager = (PaperProgramManager) proxy.getProxy();
        createTable(Judge.class); createTable(PaperProgramSnapshot.class);
        jdbc.update("INSERT INTO paper_program_snapshot(id,run_id,item_key,uid,content_json) VALUES(11,7,'PROGRAMMING:1','alice','{}')");
    }
    @AfterEach void cleanup() { ThreadContext.remove(); }
    private void createTable(Class<?> entity) {
        TableInfo table = TableInfoHelper.getTableInfo(entity);
        jdbc.execute("DROP TABLE IF EXISTS " + table.getTableName());
        StringBuilder sql = new StringBuilder("CREATE TABLE " + table.getTableName() + "(" + table.getKeyColumn() + " BIGINT PRIMARY KEY");
        for (TableFieldInfo field : table.getFieldList()) {
            Class<?> type = field.getPropertyType();
            sql.append(',').append(field.getColumn()).append(' ').append(type == String.class ? "LONGTEXT" : Date.class.isAssignableFrom(type) ? "TIMESTAMP" : type == Long.class ? "BIGINT" : type == Boolean.class ? "BOOLEAN" : "INT");
        }
        jdbc.execute(sql.append(')').toString());
    }
    private Date before() { return new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15)); }
    private void submission(long id, Long run, int status, long ageMinutes) {
        Date time = new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(ageMinutes));
        jdbc.update("INSERT INTO judge(submit_id,uid,username,pid,cid,cpid,paper_run_id,paper_snapshot_id,code,language,status,version,submit_time,gmt_create,gmt_modified) VALUES(?,'alice','alice',1,0,0,?,11,'int main(){}','C++',?,0,?,?,?)", id, run, status, time, time, time);
    }
    @Test void lostDispatchRecoversOnlyAfterCommitUsingOriginalSubmissionAndSnapshot() {
        submission(19, 7L, 5, 20); Date submitted = judges.selectById(19L).getSubmitTime();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.execute(status -> { manager.recoverPending(19L, before()); verifyNoInteractions(dispatcher); status.setRollbackOnly(); return null; });
        assertEquals(0, judges.selectById(19L).getVersion()); verifyNoInteractions(dispatcher);
        tx.execute(status -> { manager.recoverPending(19L, before()); verifyNoInteractions(dispatcher); return null; });
        verify(dispatcher).sendTask(19L, 1L, false);
        Judge restored = judges.selectById(19L);
        assertEquals(1, restored.getVersion()); assertEquals(submitted, restored.getSubmitTime());
        assertEquals(7L, restored.getPaperRunId()); assertEquals(11L, restored.getPaperSnapshotId()); assertEquals("int main(){}", restored.getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM judge", Integer.class)); verifyNoInteractions(problems);
    }
    @Test void concurrentSchedulersDispatchTheExpiredSubmissionOnlyOnce() throws Exception {
        submission(19, 7L, 5, 20); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> recover = () -> { start.await(); manager.recoverPending(19L, before()); return null; };
            Future<Void> a = pool.submit(recover), b = pool.submit(recover); start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        verify(dispatcher, times(1)).sendTask(19L, 1L, false); assertEquals(1, judges.selectById(19L).getVersion());
    }
    @Test void freshCompletedAndOrdinarySubmissionsAreNotRecoveredAsPaperWork() {
        submission(19, 7L, 5, 1); submission(20, 7L, 0, 20); submission(21, null, 5, 20);
        for (long id = 19; id <= 21; id++) manager.recoverPending(id, before());
        verifyNoInteractions(dispatcher);
        assertEquals(0, judges.selectById(19L).getVersion()); assertEquals(0, judges.selectById(20L).getVersion());
    }
    private JudgeEntityService service() {
        JudgeEntityService service = mock(JudgeEntityService.class);
        when(service.list(any(Wrapper.class))).thenAnswer(call -> judges.selectList(call.getArgument(0))); return service;
    }
    @Test void scheduledRecoveryRoutesPaperAndOrdinarySubmissionsSeparately() {
        submission(19, 7L, 5, 20); submission(20, null, 5, 20); submission(21, 7L, 5, 1);
        ScheduleServiceImpl schedule = new ScheduleServiceImpl(); RejudgeService ordinary = mock(RejudgeService.class);
        ReflectionTestUtils.setField(schedule, "judgeEntityService", service()); ReflectionTestUtils.setField(schedule, "rejudgeService", ordinary); ReflectionTestUtils.setField(schedule, "paperProgramManager", manager);
        schedule.check20MPendingSubmission();
        verify(dispatcher).sendTask(19L, 1L, false); verify(ordinary).rejudge(20L); verifyNoMoreInteractions(ordinary);
    }
    @Test void weeklyHomeCountsExcludePaperAndMixedContestSubmissions() {
        submission(19, null, 0, 1); submission(20, null, -1, 1); submission(21, 7L, 0, 1); submission(22, 8L, 0, 1); submission(23, null, 0, 8 * 24 * 60);
        Object previous = ReflectionTestUtils.getField(RedisUtils.class, "redisTemplate");
        try {
            RedisUtils redis = new RedisUtils(); redis.setRedisTemplate(mock(RedisTemplate.class, RETURNS_DEEP_STUBS));
            HomeManager home = new HomeManager(); ReflectionTestUtils.setField(home, "judgeEntityService", service()); ReflectionTestUtils.setField(home, "redisUtils", redis); ThreadContext.bind(mock(Subject.class));
            SubmissionStatisticsVO result = home.getLastWeekSubmissionStatistics(false);
            assertEquals(2L, result.getTotalCountList().stream().mapToLong(Long::longValue).sum());
            assertEquals(1L, result.getAcCountList().stream().mapToLong(Long::longValue).sum());
        } finally { ReflectionTestUtils.setField(RedisUtils.class, "redisTemplate", previous); }
    }
}
