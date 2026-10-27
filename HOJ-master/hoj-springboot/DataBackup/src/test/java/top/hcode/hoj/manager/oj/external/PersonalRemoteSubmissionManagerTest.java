package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.*;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.config.*;
import top.hcode.hoj.manager.oj.*;
import top.hcode.hoj.pojo.dto.PersonalRemoteSubmissionDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.vo.SubmissionInfoVO;
import top.hcode.hoj.remote.PersonalRemoteTaskStore;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.AccessValidator;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalRemoteSubmissionManagerTest {
    JdbcTemplate jdbc;
    PersonalRemoteSubmissionManager manager;
    BeforeDispatchInitManager before;
    PersonalRemoteFormClient forms;
    JudgeManager judges;
    ValueOperations<String,String> values;
    Map<String,String> offers;
    AccountProfile actor;
    String uid="alice",handle="alice",sourceProblem="CF-4A";
    long pid=1,accountId=1;

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:personal_entry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(120),is_remote BOOLEAN,auth INT,is_group BOOLEAN,gid BIGINT)");
        jdbc.execute("CREATE TABLE external_atcoder_problem(problem_key VARCHAR(120),task_id VARCHAR(80),contest_id VARCHAR(80),available INT)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT AUTO_INCREMENT PRIMARY KEY,uid VARCHAR(32),pid BIGINT,cid BIGINT,gid BIGINT,language VARCHAR(255),code TEXT,status INT,version INT DEFAULT 0)");
        for(String file:Arrays.asList("external-learning.sql","remote-credentials.sql","personal-remote-tasks.sql","nowcoder-remote.sql")) {
            String ddl=StreamUtils.copyToString(new ClassPathResource(file).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","");
            try(Connection connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0)");
        jdbc.update("INSERT INTO problem VALUES(1,'CF-4A',TRUE,1,FALSE,NULL)");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice','CODEFORCES','alice','alice','alice',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'bob','CODEFORCES','bob','bob','bob',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO remote_user_credential VALUES(1,'s1:synthetic',1,CURRENT_TIMESTAMP),(2,'s1:synthetic',1,CURRENT_TIMESTAMP)");
        wire(source);
    }

    void wire(DriverManagerDataSource source) throws Exception {
        actor=new AccountProfile();actor.setUid(uid);actor.setUsername(handle);
        manager=new PersonalRemoteSubmissionManager(){@Override protected AccountProfile profile(){return actor;}};
        before=mock(BeforeDispatchInitManager.class);forms=mock(PersonalRemoteFormClient.class);judges=mock(JudgeManager.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);offers=new ConcurrentHashMap<>();when(redis.opsForValue()).thenReturn(values);
        doAnswer(call->{offers.put(call.getArgument(0),call.getArgument(1));return null;}).when(values).set(anyString(),anyString(),anyLong(),any(TimeUnit.class));
        when(values.get(anyString())).thenAnswer(call->offers.get(call.getArgument(0)));
        when(forms.languages(anyLong(),anyString(),anyLong(),anyString(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Source IO cannot hold submission locks");return Collections.singletonMap("54","GNU G++17");});
        doAnswer(call->{prepare(call.getArgument(2));return null;}).when(before).prepareCommonSubmission(anyString(),nullable(Long.class),any());
        doAnswer(call->{prepare(call.getArgument(3));return null;}).when(before).prepareContestSubmission(anyLong(),anyString(),any(),any());
        doAnswer(call->{prepare(call.getArgument(3));return null;}).when(before).prepareTrainingSubmission(anyLong(),anyString(),any(),any());
        doAnswer(call->{insertJudge(call.getArgument(2));return null;}).when(before).initCommonSubmission(anyString(),nullable(Long.class),any());
        doAnswer(call->{insertJudge(call.getArgument(3));return null;}).when(before).initContestSubmission(anyLong(),anyString(),any(),any());
        doAnswer(call->{insertJudge(call.getArgument(3));return null;}).when(before).initTrainingSubmission(anyLong(),anyString(),any(),any());
        NacosSwitchConfig switches=mock(NacosSwitchConfig.class);SwitchConfig config=new SwitchConfig();config.setDefaultSubmitInterval(0);when(switches.getSwitchConfig()).thenReturn(config);
        ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(manager,"before",before);ReflectionTestUtils.setField(manager,"forms",forms);ReflectionTestUtils.setField(manager,"judges",judges);
        ReflectionTestUtils.setField(manager,"redis",redis);ReflectionTestUtils.setField(manager,"access",mock(AccessValidator.class));ReflectionTestUtils.setField(manager,"switchConfig",switches);ReflectionTestUtils.setField(manager,"enabled",true);
    }
    void prepare(Judge judge){judge.setPid(pid).setDisplayPid(sourceProblem).setCpid(0L);}
    long insert(String sql,Object... args){KeyHolder key=new GeneratedKeyHolder();jdbc.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    void insertJudge(Judge judge){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());judge.setSubmitId(insert("INSERT INTO judge(uid,pid,cid,gid,language,code,status,version) VALUES(?,?,?,?,?,?,?,?)",judge.getUid(),judge.getPid(),judge.getCid(),judge.getGid(),judge.getLanguage(),judge.getCode(),judge.getStatus(),judge.getVersion()));}
    PersonalRemoteSubmissionDTO input(){PersonalRemoteSubmissionDTO input=new PersonalRemoteSubmissionDTO();input.pid=sourceProblem;input.accountId=accountId;input.requestId="request_0000000001";input.languageId="54";input.code="synthetic source";return input;}
    void offer(PersonalRemoteSubmissionDTO input){input.offerId=(String)manager.options(input).get("offerId");}
    long submit(PersonalRemoteSubmissionDTO input){return ((Number)manager.submit(input,"127.0.0.1").get("submitId")).longValue();}
    int tasks(){return jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task WHERE owner_uid=?",Integer.class,uid);}
    int judges(){return jdbc.queryForObject("SELECT COUNT(*) FROM judge WHERE uid=?",Integer.class,uid);}
    void denied(int status,Runnable action){assertEquals(status,assertThrows(ObjectiveException.class,action::run).getStatus());}

    @Test void approvedOptionsCreateExactlyOneJournalAndReplayWithoutRequiringOfferOrCredential() {
        PersonalRemoteSubmissionDTO input=input();offer(input);long id=submit(input);
        assertEquals(1,tasks());assertEquals(1,judges());assertEquals("GNU G++17",jdbc.queryForObject("SELECT language FROM judge WHERE submit_id=?",String.class,id));
        input.offerId=null;jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL WHERE account_id=1");assertEquals(id,submit(input));
        input.code="different source";denied(409,()->submit(input));assertEquals(1,judges());
    }
    @Test void nowcoderOfferAndTaskPinTheSourceIdentityWithoutLeakingItToTheBrowser(){
        sourceProblem="NC-218144";jdbc.update("UPDATE problem SET problem_id=? WHERE id=1",sourceProblem);
        jdbc.update("UPDATE external_account SET platform='NOWCODER',handle='12345',handle_key='12345',verified_identity='12345' WHERE id=1");
        Map<String,String> snapshot=new LinkedHashMap<>();snapshot.put("questionId","1338275");snapshot.put("problemId","218144");snapshot.put("contestId","");when(forms.preparation(anyLong(),anyString(),anyLong(),anyString(),any())).thenReturn(new PersonalRemoteFormClient.Prepared(Collections.singletonMap("2","C++"),snapshot));
        PersonalRemoteSubmissionDTO input=input();input.languageId="2";assertEquals(true,manager.accounts(input).get("submissionSupported"));
        Map<String,Object> options=manager.options(input);assertEquals(true,options.get("submissionSupported"));assertFalse(options.containsKey("sourceContext"));input.offerId=(String)options.get("offerId");
        long id=submit(input);assertEquals(id,submit(input));assertEquals(1,judges());assertEquals(1,tasks());assertTrue(jdbc.queryForObject("SELECT context_json FROM personal_remote_nowcoder",String.class).contains("1338275"));
    }
    @Test void atcoderCanonicalCatalogueSupportsAccountOptionsAndPinnedSubmission(){
        sourceProblem="AC-abc001_a";jdbc.update("UPDATE problem SET problem_id=? WHERE id=1",sourceProblem);
        jdbc.update("UPDATE external_account SET platform='ATCODER' WHERE id=1");
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc001_a','abc001_a','abc001',1)");
        PersonalRemoteSubmissionDTO input=input();assertEquals(true,manager.accounts(input).get("submissionSupported"));offer(input);long id=submit(input);
        assertEquals("ATCODER",jdbc.queryForObject("SELECT platform FROM personal_remote_task WHERE submit_id=?",String.class,id));
        assertEquals("abc001",jdbc.queryForObject("SELECT source_contest FROM personal_remote_task WHERE submit_id=?",String.class,id));
        assertEquals(sourceProblem,jdbc.queryForObject("SELECT remote_problem FROM personal_remote_task WHERE submit_id=?",String.class,id));
    }
    @Test void incompleteNowcoderOfferRollsBackJudgeTaskAndIdentityLane(){
        sourceProblem="NC-218144";jdbc.update("UPDATE problem SET problem_id=? WHERE id=1",sourceProblem);jdbc.update("UPDATE external_account SET platform='NOWCODER',handle='12345',handle_key='12345',verified_identity='12345' WHERE id=1");
        when(forms.preparation(anyLong(),anyString(),anyLong(),anyString(),any())).thenReturn(new PersonalRemoteFormClient.Prepared(Collections.singletonMap("2","C++"),Collections.emptyMap()));PersonalRemoteSubmissionDTO input=input();input.languageId="2";offer(input);denied(409,()->submit(input));assertEquals(0,judges());assertEquals(0,tasks());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_lane",Integer.class));
    }
    @Test void languageOffersAreBoundToOwnerAccountScopeAndCurrentAuthorization() {
        PersonalRemoteSubmissionDTO input=input();offer(input);input.accountId=2L;
        denied(409,()->manager.submit(input,"local"));input.accountId=1L;
        input.gid=7L;denied(403,()->submit(input));input.gid=null;
        jdbc.update("UPDATE remote_user_credential SET credential_version=2 WHERE account_id=1");denied(409,()->submit(input));assertEquals(0,judges());assertEquals(0,tasks());
    }
    @Test void inventedLanguageAndExpiredOfferNeverInsertAJudge() {
        PersonalRemoteSubmissionDTO input=input();offer(input);input.languageId="999";denied(409,()->submit(input));input.languageId="54";offers.clear();denied(409,()->submit(input));assertEquals(0,judges());
    }
    @Test void singleTrainingAndContestPermissionsUseTheirExistingPreparationAndCreationPaths() throws Exception {
        PersonalRemoteSubmissionDTO training=input();training.tid=9L;offer(training);submit(training);verify(before).initTrainingSubmission(eq(9L),eq(sourceProblem),eq(actor),any());
        PersonalRemoteSubmissionDTO contest=input();contest.cid=10L;contest.requestId="request_0000000002";offer(contest);submit(contest);verify(before).initContestSubmission(eq(10L),eq(sourceProblem),eq(actor),any());
        assertEquals(2,tasks());verify(before,never()).initCommonSubmission(any(),any(),any());
    }
    @Test void deniedContestPreparationCannotReadTheSourceOrPersistAnything() throws Exception {
        PersonalRemoteSubmissionDTO input=input();input.cid=10L;doThrow(new StatusForbiddenException("比赛未开始")).when(before).prepareContestSubmission(anyLong(),anyString(),any(),any());
        denied(403,()->offer(input));verifyNoInteractions(forms);assertEquals(0,judges());
    }
    @Test void enqueueFailureRollsBackTheJudgeAndItsCreationTransaction() throws Exception {
        PersonalRemoteSubmissionDTO input=input();offer(input);
        doAnswer(call->{insertJudge(call.getArgument(2));jdbc.update("UPDATE remote_user_credential SET credential_version=2 WHERE account_id=1");return null;}).when(before).initCommonSubmission(anyString(),nullable(Long.class),any());
        denied(409,()->submit(input));assertEquals(0,judges());assertEquals(0,tasks());assertEquals(1,jdbc.queryForObject("SELECT credential_version FROM remote_user_credential WHERE account_id=1",Integer.class));
    }
    @Test void privateOrHiddenOrLocalProblemsCannotEnterThePersonalRoute() {
        PersonalRemoteSubmissionDTO input=input();jdbc.update("UPDATE problem SET auth=3");denied(403,()->manager.accounts(input));jdbc.update("UPDATE problem SET auth=2");denied(403,()->manager.accounts(input));jdbc.update("UPDATE problem SET auth=1,is_remote=FALSE");denied(400,()->manager.accounts(input));verifyNoInteractions(forms);
    }
    @Test void ownerStatusHonorsExistingSealedVerdictAndDoesNotExposeWorkerSecrets() throws Exception {
        PersonalRemoteSubmissionDTO input=input();offer(input);long id=submit(input);jdbc.update("UPDATE personal_remote_task SET state='COMPLETED',remote_submit_id=999 WHERE submit_id=?",id);
        SubmissionInfoVO view=new SubmissionInfoVO();view.setSubmission(new Judge().setStatus(10));when(judges.getSubmission(id)).thenReturn(view);
        Map<String,Object> status=manager.status(id);assertEquals(true,status.get("resultHidden"));assertFalse(((Map<?,?>)status.get("task")).containsKey("remote_submit_id"));
        String text=status.toString();assertFalse(text.contains("synthetic"));assertFalse(text.contains("lease_token"));assertFalse(text.contains("request_hash"));
        assertFalse(((Map<?,?>)manager.submit(input,"local").get("task")).containsKey("remote_submit_id"));
        actor.setUid("bob");assertThrows(PersonalRemoteTaskStore.Conflict.class,()->manager.status(id));denied(404,()->manager.request(input.requestId));
    }
    @Test void blockedOrAnonymousUsersAndDisabledEntryFailBeforeSourceReads() {
        PersonalRemoteSubmissionDTO input=input();actor=null;denied(401,()->manager.accounts(input));actor=new AccountProfile();actor.setUid(uid);jdbc.update("UPDATE user_info SET status=1 WHERE uuid=?",uid);denied(403,()->manager.accounts(input));jdbc.update("UPDATE user_info SET status=0 WHERE uuid=?",uid);ReflectionTestUtils.setField(manager,"enabled",false);denied(503,()->manager.accounts(input));verifyNoInteractions(forms);
    }
    @Test void utf8CodeLimitAndMutuallyExclusiveScopesAreCheckedBeforeWriting() {
        PersonalRemoteSubmissionDTO input=input();input.code=String.join("",Collections.nCopies(22000,"题"));denied(400,()->submit(input));input.code="source";input.cid=1L;input.tid=2L;denied(400,()->submit(input));assertEquals(0,judges());
    }

    @Test @EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
    void realMySqlConcurrentIdenticalRequestsReturnOneJudgeUnderRepeatableRead() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
        jdbc=new JdbcTemplate(source);uid=UUID.randomUUID().toString().replace("-","");handle="entry_"+uid.substring(0,12);sourceProblem="CF-"+(900000000+new Random().nextInt(99999999))+"A";
        try {
            jdbc.update("INSERT INTO user_info(uuid,username,password) VALUES(?,?,?)",uid,handle,"synthetic local only");
            pid=insert("INSERT INTO problem(problem_id,title,author,is_remote,auth) VALUES(?,?,?,TRUE,1)",sourceProblem,"Personal submit local fixture",handle);
            accountId=insert("INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(?,'CODEFORCES',?,?,?,NOW(3),NOW(3))",uid,handle,handle,handle);
            jdbc.update("INSERT INTO remote_user_credential VALUES(?,'s1:synthetic',1,NOW(3))",accountId);wire(source);
            assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
            PersonalRemoteSubmissionDTO input=input();offer(input);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
            try {Future<Long> a=pool.submit(()->{go.await();return submit(input);}),b=pool.submit(()->{go.await();return submit(input);});go.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,judges());assertEquals(1,tasks());}
            finally{pool.shutdownNow();}
            System.out.println("Personal submit entry: MySQL RR concurrent identical requests created one judge/task; source client mocked and remote worker must remain stopped.");
        } finally {
            jdbc.update("DELETE FROM judge WHERE uid=?",uid);jdbc.update("DELETE FROM external_account WHERE uid=?",uid);jdbc.update("DELETE FROM personal_remote_lane WHERE platform='CODEFORCES' AND handle_key=? AND active_submit_id IS NULL",handle);jdbc.update("DELETE FROM problem WHERE id=? AND author=?",pid,handle);jdbc.update("DELETE FROM user_info WHERE uuid=?",uid);
        }
    }
}
