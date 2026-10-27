package top.hcode.hoj.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import top.hcode.hoj.manager.oj.external.AtCoderCatalogManager;
import top.hcode.hoj.utils.RemoteCredentialCipher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP and MySQL, synthetic source verdicts. Never uses the application queue or an external host. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class PersonalRemoteRunnerMySqlTest {
    enum Platform { CODEFORCES, ATCODER, NOWCODER }
    private static final String CODE="// Synthetic protocol fixture: 中文 & + %\nint main() { return 0; }\n";
    @TempDir Path secret;
    JdbcTemplate admin,jdbc;
    DataSourceTransactionManager transactions;
    String schema;
    boolean created;
    long mainProblems,mainJudges;
    AtomicLong clock=new AtomicLong();
    AtomicBoolean failWrite=new AtomicBoolean();
    SimulatedSource source;

    static DriverManagerDataSource data(String schema){
        if(!schema.equals("hoj") && !schema.matches("hoj_remote_runner_it_[a-f0-9]{32}"))throw new IllegalArgumentException("Not an isolated test schema");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"));
    }
    @BeforeEach void setup() throws Exception {
        assertNotNull(System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"));admin=new JdbcTemplate(data("hoj"));
        mainProblems=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);mainJudges=admin.queryForObject("SELECT COUNT(*) FROM judge",Long.class);
        schema="hoj_remote_runner_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        DriverManagerDataSource ds=data(schema);jdbc=new JdbcTemplate(ds);transactions=new DataSourceTransactionManager(ds);clock.set(System.currentTimeMillis());
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.execute("CREATE TABLE problem(id BIGINT UNSIGNED PRIMARY KEY,problem_id VARCHAR(120),is_remote BOOLEAN,auth INT,author VARCHAR(100),title VARCHAR(255))");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT UNSIGNED PRIMARY KEY,uid VARCHAR(32),pid BIGINT UNSIGNED,cid BIGINT,gid BIGINT,language VARCHAR(80),code TEXT,status INT,version INT DEFAULT 0,time INT,memory INT,vjudge_submit_id BIGINT)");
        jdbc.execute("CREATE TABLE result_effect(submit_id BIGINT UNSIGNED PRIMARY KEY,verdict INT,source_id BIGINT)");
        for(String file:Arrays.asList("external-learning.sql","remote-sources.sql","atcoder-learning.sql","remote-credentials.sql","personal-remote-tasks.sql","nowcoder-remote.sql")){
            try(Connection connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource(file));}
        }
        // Use the actual catalogue producer, rather than duplicating its key convention in a fixture.
        AtCoderCatalogManager catalogue=new AtCoderCatalogManager(){@Override protected long now(){return clock.get();}};
        ReflectionTestUtils.setField(catalogue,"jdbc",jdbc);ReflectionTestUtils.setField(catalogue,"transactions",transactions);
        jdbc.update("UPDATE remote_source_job SET next_run_at=NULL");jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name='ATCODER_PROBLEMS'",new java.sql.Timestamp(clock.get()));
        catalogue.apply(catalogue.claim(),new ObjectMapper().readTree("[{\"id\":\"abc001_1\",\"contest_id\":\"abc001\",\"problem_index\":\"A\",\"title\":\"Synthetic catalogue fixture\"}]"));
    }
    @AfterEach void cleanup(){
        if(source!=null)source.close();
        if(created){assertTrue(schema.matches("hoj_remote_runner_it_[a-f0-9]{32}"));admin.execute("DROP DATABASE "+schema);}
        if(admin!=null){assertEquals(mainProblems,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));assertEquals(mainJudges,admin.queryForObject("SELECT COUNT(*) FROM judge",Long.class));}
    }
    PersonalRemoteTaskStore store(){return new PersonalRemoteTaskStore(jdbc,transactions,clock::get);}
    void prepare(Platform platform,int verdict) throws Exception {
        source=new SimulatedSource(platform,verdict);String handle=platform==Platform.NOWCODER?"12345":"tourist";
        String problem=platform==Platform.NOWCODER?"NC-218144":platform==Platform.ATCODER?"AC-abc001_1":"CF-4A";
        String language=platform==Platform.NOWCODER?"C++":"C++ 23",languageId=platform==Platform.NOWCODER?"2":"5400";
        jdbc.update("INSERT INTO user_info VALUES('alice',0)");jdbc.update("INSERT INTO problem VALUES(1,?,TRUE,2,'alice','Synthetic fixture')",problem);
        jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,language,code,status) VALUES(1,'alice',1,0,?,?,5)",language,CODE);
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice',?,?,?,?,NOW(3),NOW(3))",platform.name(),handle,handle,handle);
        String cookies=platform==Platform.NOWCODER?"t=synthetic; csrf_token=synthetic":platform==Platform.ATCODER?"REVEL_SESSION=synthetic":"JSESSIONID=synthetic";
        String encrypted=new RemoteCredentialCipher(secret).encryptSession(1,"alice",platform.name(),handle,cookies,true);
        jdbc.update("INSERT INTO remote_user_credential VALUES(1,?,1,NOW(3))",encrypted);
        NowCoderJudgeProtocol.Context context=platform==Platform.NOWCODER?new NowCoderJudgeProtocol.Context("12345","1338275","218144","","2","C++"):null;
        new TransactionTemplate(transactions).execute(status->store().enqueue("alice",1,1,"request_local_simulation_01",languageId,null,1L,context));
    }
    static PersonalRemoteTransport localTransport(Platform platform,RemoteSession session,Runnable before,int port){
        if(port<1 || port>65535)throw new IllegalArgumentException("Invalid local port");URI origin=URI.create("http://127.0.0.1:"+port);
        return new PersonalRemoteTransport(origin,session,before){
            @Override protected URI questionEndpoint(String id){return origin.resolve("/question?id="+id);}
            @Override protected URI nowcoderService(String service){super.nowcoderService(service);return origin.resolve("/nc/"+service);}
        };
    }
    static PersonalRemoteTaskRunner worker(JdbcTemplate jdbc,DataSourceTransactionManager tm,Path secret,AtomicLong clock,Platform platform,int port,AtomicBoolean failWrite){
        return new PersonalRemoteTaskRunner(jdbc,new PersonalRemoteTaskStore(jdbc,tm,clock::get),new RemoteCredentialCipher(secret),
                (name,session,before)->{assertEquals(platform.name(),name);return localTransport(platform,session,before,port);},
                name->assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Source HTTP must not hold database locks"),
                (id,result,stopped)->{
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("UPDATE judge SET status=?,time=?,memory=?,vjudge_submit_id=? WHERE submit_id=?",result==null?stopped:result.verdict,result==null?null:result.time,result==null?null:result.memory,result==null?null:result.id,id);
                    jdbc.update("INSERT INTO result_effect VALUES(?,?,?)",id,result==null?stopped:result.verdict,result==null?null:result.id);
                    if(failWrite.getAndSet(false))throw new IllegalStateException("Synthetic result-transaction failure");
                });
    }
    void step(){assertTrue(worker(jdbc,transactions,secret,clock,source.platform,source.port(),failWrite).runOne(source.platform.name()));source.healthy();}
    String state(){return jdbc.queryForObject("SELECT state FROM personal_remote_task",String.class);}
    void advance(){clock.addAndGet(6000);}
    void identify(){step();if(source.platform!=Platform.NOWCODER){assertEquals("RECONCILING",state());advance();step();}assertEquals("POLLING",state());}
    void completed(int verdict){
        assertEquals("COMPLETED",state());assertEquals(verdict,jdbc.queryForObject("SELECT status FROM judge",Integer.class));
        assertEquals(101L,jdbc.queryForObject("SELECT vjudge_submit_id FROM judge",Long.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM result_effect",Integer.class));
        assertEquals(verdict,jdbc.queryForObject("SELECT verdict FROM result_effect",Integer.class));assertEquals(1,source.posts.get());
        assertEquals(1,jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));assertNull(jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));
        assertFalse(worker(jdbc,transactions,secret,clock,source.platform,source.port(),failWrite).runOne(source.platform.name()));
    }
    static Stream<Arguments> verdicts(){return Arrays.stream(Platform.values()).flatMap(platform->Stream.of(0,-1,1).map(verdict->Arguments.of(platform,verdict)));}
    @ParameterizedTest(name="{0}: synthetic verdict {1}") @MethodSource("verdicts")
    void actualHttpAndMySqlCommitSyntheticAcWaAndTleExactlyOnce(Platform platform,int verdict) throws Exception {prepare(platform,verdict);identify();step();completed(verdict);}

    @ParameterizedTest @EnumSource(Platform.class)
    void droppedPostReplyRecoversOnlyProvableResultsAndNeverReposts(Platform platform) throws Exception {
        prepare(platform,0);source.dropPostReply=true;step();assertEquals("RECONCILING",state());advance();step();
        if(platform==Platform.NOWCODER){
            assertEquals("NEEDS_REVIEW",state());assertEquals("POST_UNCERTAIN",jdbc.queryForObject("SELECT error_code FROM personal_remote_task",String.class));
            assertEquals(1L,jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM result_effect",Integer.class));assertEquals(1,source.posts.get());
        }else{assertEquals("POLLING",state());step();completed(0);}
    }
    @ParameterizedTest @EnumSource(Platform.class)
    void lostResultReadCanBeRetriedByANewWorkerWithoutASecondPost(Platform platform) throws Exception {
        prepare(platform,-1);identify();source.dropNextRead.set(true);step();assertEquals("POLLING",state());
        assertEquals(1,jdbc.queryForObject("SELECT read_failures FROM personal_remote_task",Integer.class));advance();step();completed(-1);
    }
    @ParameterizedTest @EnumSource(Platform.class)
    void resultAndSyntheticEffectRollbackTogetherBeforeReadOnlyRecovery(Platform platform) throws Exception {
        prepare(platform,1);identify();failWrite.set(true);step();assertEquals("POLLING",state());assertEquals(5,jdbc.queryForObject("SELECT status FROM judge",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM result_effect",Integer.class));advance();step();completed(1);
    }
    @ParameterizedTest @EnumSource(Platform.class)
    void separateJvmProcessesResumeDurableStateAndNeverRepeatTheCodePost(Platform platform) throws Exception {
        prepare(platform,0);Set<String> processes=new HashSet<>();processes.add(childStep());
        if(platform!=Platform.NOWCODER){assertEquals("RECONCILING",state());advance();processes.add(childStep());}
        assertEquals("POLLING",state());processes.add(childStep());completed(0);assertEquals(platform==Platform.NOWCODER?2:3,processes.size());
    }
    @Test void actualCatalogueProducerPreservesCanonicalIdentityUnderMySqlCollation(){
        assertEquals("atcoder:task:abc001_1",jdbc.queryForObject("SELECT problem_key FROM external_atcoder_problem",String.class));
        assertEquals("abc001",store().problemReference("AC-abc001_1").contest);
        jdbc.update("UPDATE external_atcoder_problem SET task_id='ABC001_1'");assertThrows(PersonalRemoteTaskStore.Conflict.class,()->store().problemReference("AC-abc001_1"));
    }
    String childStep() throws Exception {
        List<String> args=new ArrayList<>();args.add(Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
        String logging=System.getProperty("logback.configurationFile");if(logging!=null)args.add("-Dlogback.configurationFile="+logging);
        // A manifest classpath avoids Windows' command-line length limit without a shell.
        java.util.jar.Manifest manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        manifest.getMainAttributes().putValue("Class-Path",Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))).map(entry->Paths.get(entry).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        Path launcher=Files.createTempFile(secret,"local-worker-classpath-",".jar");try(java.util.jar.JarOutputStream ignored=new java.util.jar.JarOutputStream(Files.newOutputStream(launcher),manifest)){}
        args.addAll(Arrays.asList("-cp",launcher.toString(),getClass().getName(),"worker",schema,source.platform.name(),String.valueOf(source.port()),secret.toAbsolutePath().toString(),String.valueOf(clock.get())));
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        try{
            assertTrue(process.waitFor(40,TimeUnit.SECONDS),"Local child worker did not finish");String output=read(process.getInputStream());
            assertEquals(0,process.exitValue(),output);source.healthy();
            return Arrays.stream(output.split("\\R")).filter(line->line.startsWith("worker_process=")).findFirst().orElseThrow(()->new AssertionError("Missing child worker receipt"));
        }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
    /** Only the opt-in test launcher uses this entry point, with a fixed loopback DB and source. */
    public static void main(String[] args){
        if(!"1".equals(System.getenv("HOJ_LOCAL_REMOTE_TASK_TEST")) || args.length!=6 || !"worker".equals(args[0]) || !args[1].matches("hoj_remote_runner_it_[a-f0-9]{32}"))throw new IllegalArgumentException("Isolated local test invocation required");
        DriverManagerDataSource ds=data(args[1]);JdbcTemplate jdbc=new JdbcTemplate(ds);Platform platform=Platform.valueOf(args[2]);
        boolean ran=worker(jdbc,new DataSourceTransactionManager(ds),Paths.get(args[4]),new AtomicLong(Long.parseLong(args[5])),platform,Integer.parseInt(args[3]),new AtomicBoolean()).runOne(platform.name());
        if(!ran)throw new IllegalStateException("No due local test task");System.out.println("worker_process="+java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
    }
    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int size;
        while((size=input.read(buffer))!=-1){out.write(buffer,0,size);if(out.size()>262144)throw new IOException("Local fixture response limit");}
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    class SimulatedSource implements AutoCloseable {
        final Platform platform;final int verdict;final HttpServer server;final ObjectMapper json=new ObjectMapper();
        final AtomicInteger posts=new AtomicInteger();final AtomicLong postedAt=new AtomicLong();final AtomicBoolean dropNextRead=new AtomicBoolean();final AtomicReference<Throwable> error=new AtomicReference<>();
        volatile boolean dropPostReply;
        SimulatedSource(Platform platform,int verdict) throws IOException {this.platform=platform;this.verdict=verdict;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::handle);server.start();}
        int port(){return server.getAddress().getPort();}
        void healthy(){if(error.get()!=null)throw new AssertionError("Local source assertion failed",error.get());}
        String nav(){return platform==Platform.NOWCODER?"<script>\nwindow.isLogin=true;\nwindow.globalInfo={ownerId:'12345'};\n</script>":platform==Platform.ATCODER?"<div id='navbar-collapse'><ul class='navbar-right'><span class='username'>tourist</span></ul></div><form method='POST' action='/logout'></form>":"<div class='lang-chooser'><a href='/profile/tourist'>tourist</a><a href='/logout?csrf_token=synthetic'>Logout</a></div>";}
        void handle(HttpExchange exchange){
            try{
                String path=exchange.getRequestURI().getPath();boolean service=path.equals("/question") || path.equals("/nc/submit") || path.equals("/nc/status");
                if(service){assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));}
                else assertEquals(platform==Platform.NOWCODER?"t=synthetic; csrf_token=synthetic":platform==Platform.ATCODER?"REVEL_SESSION=synthetic":"JSESSIONID=synthetic",exchange.getRequestHeaders().getFirst("Cookie"));
                boolean resultRead=path.equals("/api/user.status") || path.endsWith("/submissions/me") || path.equals("/nc/status");
                if(resultRead && dropNextRead.getAndSet(false)){exchange.close();return;}
                String body;
                if("POST".equals(exchange.getRequestMethod())){
                    assertEquals("SUBMITTING",state());assertEquals(1,jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));posts.incrementAndGet();postedAt.set(clock.get());
                    String raw=read(exchange.getRequestBody());
                    if(platform==Platform.NOWCODER){assertEquals("/nc/submit",path);JsonNode value=json.readTree(raw);assertEquals(CODE,value.path("content").asText());assertEquals("12345",value.path("userId").asText());body="{\"code\":0,\"data\":{\"id\":\"job-101\"}}";}
                    else{Map<String,String> form=new HashMap<>();for(String pair:raw.split("&")){String[] part=pair.split("=",2);form.put(URLDecoder.decode(part[0],"UTF-8"),URLDecoder.decode(part[1],"UTF-8"));}assertEquals(CODE,form.get(platform==Platform.ATCODER?"sourceCode":"source"));body="Synthetic response, not acceptance proof";}
                    if(dropPostReply){exchange.close();return;}
                }else if(path.equals("/nc/oauth"))body="{\"success\":true,\"data\":{\"accessToken\":\"synthetic-access\"}}";
                else if(path.equals("/question"))body="{\"code\":0,\"data\":{\"id\":\"1338275\",\"codingInfo\":{\"supportLanguages\":[{\"langId\":2,\"langName\":\"C++\"}]}}}";
                else if(path.equals("/nc/status"))body="{\"code\":0,\"data\":{\"id\":\"job-101\",\"submissionId\":101,\"status\":"+(verdict==0?5:verdict==-1?4:6)+",\"judgeReplyDesc\":\"答案错误\",\"userId\":\"12345\",\"questionId\":\"1338275\",\"language\":2}}";
                else if(path.equals("/"))body=nav();
                else if(path.equals("/acm/problem/218144"))body=nav()+"<script>\nwindow.pageInfo={questionId:'1338275',problemId:'218144'};\n</script>";
                else if(path.contains("practice-coding"))body=history();
                else if(path.equals("/api/user.status"))body=cfHistory();
                else if(path.endsWith("/submissions/me"))body=atHistory();
                else if(path.endsWith("/submit") || path.contains("/tasks/"))body=nav()+"<form method='POST' action='"+(platform==Platform.ATCODER?"/contests/abc001/submit":"/contest/4/submit")+"'><input type='hidden' name='csrf_token' value='synthetic'><input name='"+(platform==Platform.ATCODER?"data.TaskScreenName":"submittedProblemIndex")+"' value='"+(platform==Platform.ATCODER?"abc001_1":"A")+"'><select name='"+(platform==Platform.ATCODER?"data.LanguageId":"programTypeId")+"'><option value='5400'>C++ 23</option></select></form>";
                else if(path.endsWith("/101"))body=nav()+"<pre id='"+(platform==Platform.ATCODER?"submission-code":"program-source-text")+"'>"+escape(CODE)+"</pre>";
                else throw new AssertionError("Unexpected local source route: "+path);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable problem){error.compareAndSet(null,problem);try{exchange.sendResponseHeaders(500,-1);}catch(IOException ignored){}}
            finally{exchange.close();}
        }
        long time(){return postedAt.get()==0?clock.get():postedAt.get();}
        String cfHistory(){
            ObjectNode root=json.createObjectNode().put("status","OK"),row=root.putArray("result").addObject().put("id",posts.get()==0?100:101).put("creationTimeSeconds",time()/1000).put("programmingLanguage","C++ 23").put("verdict",verdict==0?"OK":verdict==-1?"WRONG_ANSWER":"TIME_LIMIT_EXCEEDED").put("timeConsumedMillis",12).put("memoryConsumedBytes",1024);
            row.putObject("author").putArray("members").addObject().put("handle","tourist");row.putObject("problem").put("contestId",4).put("index","A");return root.toString();
        }
        String atHistory(){
            String time=Instant.ofEpochMilli(time()).atOffset(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"));
            return nav()+"<table><thead><tr><th>Submission Time</th></tr></thead><tbody><tr><td>"+time+"</td><td><a href='/contests/abc001/tasks/abc001_1'>A</a></td><td><a href='/users/tourist'>tourist</a></td><td>C++ 23</td><td>100</td><td>20 Byte</td><td>"+(verdict==0?"AC":verdict==-1?"WA":"TLE")+"</td><td>12 ms</td><td>64 KB</td><td><a href='/contests/abc001/submissions/"+(posts.get()==0?100:101)+"'>Detail</a></td></tr></tbody></table>";
        }
        String history(){
            String date=Instant.ofEpochMilli(time()).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return nav()+"<table class='table-hover'><thead><tr>"+String.join("",Collections.nCopies(9,"<th>Header</th>"))+"</tr></thead><tbody><tr><td><a href='/acm/contest/view-submission?submissionId=101&amp;uid=12345'>101</a></td><td><a href='/acm/problem/218144'>Problem</a></td>"+String.join("",Collections.nCopies(5,"<td>fixture</td>"))+"<td>C++</td><td>"+date+"</td></tr></tbody></table>";
        }
        @Override public void close(){server.stop(0);}
    }
}
