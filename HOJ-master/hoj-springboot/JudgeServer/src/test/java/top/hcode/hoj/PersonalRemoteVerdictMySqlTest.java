package top.hcode.hoj;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.dao.impl.*;
import top.hcode.hoj.judge.JudgeContext;
import top.hcode.hoj.mapper.*;
import top.hcode.hoj.remote.PersonalRemoteTaskStore;
import top.hcode.hoj.remoteJudge.personal.PersonalRemoteWorker;
import top.hcode.hoj.util.Constants;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual JDBC+MyBatis+DAO+Context+Worker inside the task transaction, with isolated fixtures. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class PersonalRemoteVerdictMySqlTest {
    JdbcTemplate jdbc,admin;DataSourceTransactionManager transactions;PersonalRemoteTaskStore tasks;PersonalRemoteWorker worker;
    AtomicLong clock=new AtomicLong(1800000000000L);String schema;boolean created;long mainProblems,mainJudges;
    @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base="jdbc:mysql://127.0.0.1:13306/",options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+options,"root",password));
        mainProblems=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);mainJudges=admin.queryForObject("SELECT COUNT(*) FROM judge",Long.class);
        schema="hoj_remote_verdict_it_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        DriverManagerDataSource ds=new DriverManagerDataSource(base+schema+options,"root",password);jdbc=new JdbcTemplate(ds);transactions=new DataSourceTransactionManager(ds);
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.execute("CREATE TABLE problem(id BIGINT UNSIGNED PRIMARY KEY,problem_id VARCHAR(120),is_remote BOOLEAN)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT UNSIGNED PRIMARY KEY,uid VARCHAR(32),pid BIGINT UNSIGNED,cid BIGINT,gid BIGINT,language VARCHAR(80),code TEXT,status INT,version INT DEFAULT 0,time INT,memory INT,score INT,error_message TEXT,judger VARCHAR(80),vjudge_submit_id BIGINT,vjudge_username VARCHAR(80),vjudge_password VARCHAR(80),gmt_modified DATETIME(3))");
        jdbc.execute("CREATE TABLE contest_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,submit_id BIGINT UNSIGNED UNIQUE,status INT,score INT,use_time INT)");
        jdbc.execute("CREATE TABLE user_acproblem(id BIGINT AUTO_INCREMENT PRIMARY KEY,uid VARCHAR(32),pid BIGINT UNSIGNED,submit_id BIGINT UNSIGNED,gmt_create DATETIME(3),gmt_modified DATETIME(3))");
        for(String file:Arrays.asList("external-learning.sql","remote-credentials.sql","personal-remote-tasks.sql"))try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource(file));}
        jdbc.update("INSERT INTO user_info VALUES('alice',0)");jdbc.update("INSERT INTO problem VALUES(1,'CF-4A',TRUE)");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice','CODEFORCES','tourist','tourist','tourist',NOW(3),NOW(3))");
        jdbc.update("INSERT INTO remote_user_credential VALUES(1,'s1:synthetic',1,NOW(3))");
        MybatisConfiguration configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ContestRecordMapper.class);configuration.addMapper(UserAcproblemMapper.class);
        MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setSqlInjector(new DefaultSqlInjector()));
        factory.setTransactionFactory(new SpringManagedTransactionFactory());SqlSessionTemplate sql=new SqlSessionTemplate(factory.getObject());
        ContestRecordEntityServiceImpl records=new ContestRecordEntityServiceImpl();ReflectionTestUtils.setField(records,"baseMapper",sql.getMapper(ContestRecordMapper.class));
        ApplicationContext application=mock(ApplicationContext.class);when(application.getBean(ContestRecordEntityServiceImpl.class)).thenReturn(records);ReflectionTestUtils.setField(records,"applicationContext",application);
        UserAcproblemEntityServiceImpl accepted=new UserAcproblemEntityServiceImpl();ReflectionTestUtils.setField(accepted,"baseMapper",sql.getMapper(UserAcproblemMapper.class));
        JudgeContext context=new JudgeContext();ReflectionTestUtils.setField(context,"contestRecordEntityService",records);ReflectionTestUtils.setField(context,"userAcproblemEntityService",accepted);
        tasks=new PersonalRemoteTaskStore(jdbc,transactions,clock::get);worker=new PersonalRemoteWorker(jdbc,transactions,mock(StringRedisTemplate.class),context,"unused-local-test-key-directory");
    }
    @AfterEach void cleanup(){
        if(created){assertTrue(schema.matches("hoj_remote_verdict_it_[0-9a-f]{32}"));admin.execute("DROP DATABASE "+schema);}
        if(admin!=null){assertEquals(mainProblems,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));assertEquals(mainJudges,admin.queryForObject("SELECT COUNT(*) FROM judge",Long.class));}
    }
    Map<String,Object> posted(long contest){
        jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,language,code,status) VALUES(1,'alice',1,?,'C++','synthetic source',5)",contest);
        new TransactionTemplate(transactions).execute(status->tasks.enqueue("alice",1,1,"request_strict_verdict_0001","54"));
        Map<String,Object> first=tasks.claim("CODEFORCES");String token=(String)first.get("lease_token");assertTrue(tasks.beginPost(1,token,100));tasks.accepted(1,token,101);
        return tasks.claim("CODEFORCES");
    }
    void grade(){ReflectionTestUtils.invokeMethod(worker,"applyResult",1L,new PersonalRemoteWorkerTest().sourceResult("OK"),0);}
    void assertUnchanged(Map<String,Object> task,Map<String,Object> judge){
        assertEquals(task,jdbc.queryForMap("SELECT * FROM personal_remote_task"));assertEquals(judge,jdbc.queryForMap("SELECT * FROM judge"));
        assertEquals(1L,jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));
    }
    @Test void missingContestRecordRollsBackTheRealPersonalResult(){
        Map<String,Object> poll=posted(1),task=jdbc.queryForMap("SELECT * FROM personal_remote_task"),judge=jdbc.queryForMap("SELECT * FROM judge");
        assertThrows(RuntimeException.class,()->tasks.completed(1,(String)poll.get("lease_token"),this::grade));assertUnchanged(task,judge);
        jdbc.update("INSERT INTO contest_record(submit_id,status,score) VALUES(1,9,0)");
        assertTrue(tasks.completed(1,(String)poll.get("lease_token"),this::grade));
        assertEquals(Constants.Contest.RECORD_AC.getCode(),jdbc.queryForObject("SELECT status FROM contest_record",Integer.class));
        assertEquals(100,jdbc.queryForObject("SELECT score FROM contest_record",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT status FROM judge",Integer.class));
        assertNull(jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));
        assertThrows(PersonalRemoteTaskStore.Conflict.class,()->tasks.completed(1,(String)poll.get("lease_token"),this::grade));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_record",Integer.class));
    }
    @Test void practiceAcConstraintFailureRollsBackJudgeAndTask(){
        Map<String,Object> poll=posted(0),task=jdbc.queryForMap("SELECT * FROM personal_remote_task"),judge=jdbc.queryForMap("SELECT * FROM judge");
        jdbc.execute("ALTER TABLE user_acproblem ADD CONSTRAINT reject_personal_ac CHECK(pid<>1)");
        assertThrows(RuntimeException.class,()->tasks.completed(1,(String)poll.get("lease_token"),this::grade));assertUnchanged(task,judge);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_acproblem",Integer.class));
        jdbc.execute("ALTER TABLE user_acproblem DROP CHECK reject_personal_ac");assertTrue(tasks.completed(1,(String)poll.get("lease_token"),this::grade));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_acproblem",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualContestAssociationWaitHonorsTheDeadline(boolean live) throws Exception {
        Map<String,Object> poll=posted(1),task=jdbc.queryForMap("SELECT * FROM personal_remote_task"),judge=jdbc.queryForMap("SELECT * FROM judge");
        jdbc.update("INSERT INTO contest_record(submit_id,status,score) VALUES(1,9,0)");long deadline=((java.sql.Timestamp)poll.get("lease_until")).getTime();
        TransactionTemplate tx=new TransactionTemplate(transactions);ExecutorService executor=Executors.newSingleThreadExecutor();
        AtomicLong connection=new AtomicLong();CountDownLatch started=new CountDownLatch(1);AtomicReference<Future<?>> pending=new AtomicReference<>();AtomicReference<Throwable> outcome=new AtomicReference<>();
        try {
            tx.execute(held->{
                jdbc.queryForList("SELECT id FROM contest_record WHERE submit_id=1 FOR UPDATE");
                pending.set(executor.submit(()->{
                    try{tx.execute(waiting->{connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));started.countDown();assertTrue(tasks.completed(1,(String)poll.get("lease_token"),this::grade));return null;});}
                    catch(Throwable failure){outcome.set(failure);}
                }));
                try {
                    assertTrue(started.await(5,TimeUnit.SECONDS));long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                    while(System.nanoTime()<limit){blocked=admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=? AND l.LOCK_TYPE='RECORD'",Integer.class,connection.get(),schema,"contest_record")>0;if(blocked)break;Thread.sleep(25);}
                    assertTrue(blocked,"The real MyBatis association update must wait after judge has been written");
                }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                clock.set(deadline-(live?1:0));return null;
            });pending.get().get(10,TimeUnit.SECONDS);
            if(live)assertNull(outcome.get());
            else assertTrue(outcome.get() instanceof PersonalRemoteTaskStore.Conflict,"The entire worker transaction must report the lease conflict: "+outcome.get());
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        if(!live){assertUnchanged(task,judge);assertEquals(9,jdbc.queryForObject("SELECT status FROM contest_record",Integer.class));Map<String,Object> fresh=tasks.claim("CODEFORCES");assertTrue(tasks.completed(1,(String)fresh.get("lease_token"),this::grade));}
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT state FROM personal_remote_task",String.class));
        assertEquals(Constants.Contest.RECORD_AC.getCode(),jdbc.queryForObject("SELECT status FROM contest_record",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_record",Integer.class));
    }
}
