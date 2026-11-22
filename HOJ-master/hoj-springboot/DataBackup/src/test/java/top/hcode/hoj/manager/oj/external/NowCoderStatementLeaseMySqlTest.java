package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.crawler.problem.ProblemStrategy;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Compound NowCoder writes in disposable schemas; no application jobs or source requests. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class NowCoderStatementLeaseMySqlTest extends RemoteSourceManagerTest {
    private static final String NC_KEY="nowcoder:acm:6";
    private static final String NEW_TITLE="New metadata";
    private String schema;
    private JdbcTemplate admin;
    private long mainProblems;
    private boolean created;

    @Override @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base=System.getenv("HOJ_LOCAL_MYSQL_BASE");
        if(base==null)base="jdbc:mysql://127.0.0.1:13306/";
        assertTrue(base.equals("jdbc:mysql://127.0.0.1:13306/") || base.equals("jdbc:mysql://hoj-mysql:3306/"));
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=15000";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+options,"root",password));
        mainProblems=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        schema="hoj_nc_stmt_lease_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        initialize(new DriverManagerDataSource(base+schema+options,"root",password));
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
    }

    @AfterEach void cleanup(){
        if(created){assertTrue(schema.matches("hoj_nc_stmt_lease_it_[0-9a-f]{32}"));admin.execute("DROP DATABASE "+schema);}
        if(admin!=null)assertEquals(mainProblems,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));
    }

    private Map<String,Object> prepareNowCoder(AtomicBoolean metadataWritten){
        jdbc.update("INSERT INTO language VALUES(3,'ME')");
        when(remote.adminAddOtherOJProblem(any(),eq("NC"),eq(true))).thenAnswer(call->
                insertProblem(((ProblemStrategy.RemoteProblemInfo)call.getArgument(0)).getProblem()));
        NowCoderCatalogManager nc=new NowCoderCatalogManager(){
            @Override protected long now(){return clock;}
            @Override public void discovered(String key,NowCoderStatementClient.Statement source){
                super.discovered(key,source);
                if(NEW_TITLE.equals(source.content.getTitle())){
                    assertEquals(NEW_TITLE,NowCoderStatementLeaseMySqlTest.this.jdbc.queryForObject("SELECT title FROM remote_nowcoder_problem WHERE problem_key=?",String.class,key));
                    metadataWritten.set(true);
                }
            }
        };
        ReflectionTestUtils.setField(nc,"jdbc",jdbc);
        ReflectionTestUtils.setField(nc,"transactions",new DataSourceTransactionManager(jdbc.getDataSource()));
        ReflectionTestUtils.setField(statements,"nowcoderCatalogue",nc);
        nc.discovered(NC_KEY,new NowCoderStatementClient.Statement("8","6",content("Original metadata")));
        statements.request(NC_KEY);
        Map<String,Object> lease=statements.claim();assertNotNull(lease);return lease;
    }

    private Map<String,Object> snapshot(){
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:Arrays.asList("remote_nowcoder_problem","remote_problem_import","remote_statement_version","remote_statement_current"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE problem_key=?",NC_KEY));
        result.put("problem",jdbc.queryForList("SELECT * FROM problem WHERE problem_id='NC-6'"));
        return result;
    }

    private void assertPublished(){
        assertEquals(NEW_TITLE,jdbc.queryForObject("SELECT title FROM remote_nowcoder_problem WHERE problem_key=?",String.class,NC_KEY));
        assertEquals(NEW_TITLE,jdbc.queryForObject("SELECT title FROM problem WHERE problem_id='NC-6'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=?",Integer.class,NC_KEY));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_current WHERE problem_key=?",Integer.class,NC_KEY));
        Map<String,Object> job=jdbc.queryForMap("SELECT * FROM remote_problem_import WHERE problem_key=?",NC_KEY);
        assertEquals("DONE",job.get("state"));assertNull(job.get("lease_token"));assertNull(job.get("lease_until"));
    }

    @Test void rediscoveryTracksMetadataChangesAndIgnoresIdenticalRefreshes() {
        NowCoderCatalogManager catalog = new NowCoderCatalogManager() {
            @Override protected long now() { return clock; }
        };
        ReflectionTestUtils.setField(catalog, "jdbc", jdbc);
        ReflectionTestUtils.setField(catalog, "transactions", new DataSourceTransactionManager(jdbc.getDataSource()));
        NowCoderStatementClient.Statement original = new NowCoderStatementClient.Statement("8", "6", content("Graph paths"));
        NowCoderStatementClient.Statement renamed = new NowCoderStatementClient.Statement("8", "6", content("Shortest paths"));
        NowCoderStatementClient.Statement recased = new NowCoderStatementClient.Statement("8", "6", content("Shortest Paths"));

        catalog.discovered(NC_KEY, original);
        assertCatalogRevision(1, clock, clock);
        clock += 1000;
        catalog.discovered(NC_KEY, renamed);
        long renamedAt = clock;
        assertCatalogRevision(2, renamedAt, clock);
        clock += 1000;
        catalog.discovered(NC_KEY, renamed);
        assertCatalogRevision(2, renamedAt, clock);
        clock += 1000;
        catalog.discovered(NC_KEY, recased);
        assertCatalogRevision(3, clock, clock);

        jdbc.update("UPDATE remote_nowcoder_problem SET available=0 WHERE problem_key=?", NC_KEY);
        clock += 1000;
        catalog.discovered(NC_KEY, recased);
        assertCatalogRevision(4, clock, clock);
    }

    private void assertCatalogRevision(long revision, long changedAt, long lastSeenAt) {
        jdbc.queryForObject("SELECT revision,changed_at,last_seen_at,available FROM remote_nowcoder_problem WHERE problem_key=?",
                (row, index) -> {
                    assertEquals(revision, row.getLong("revision"), "catalogue revision");
                    assertEquals(changedAt, row.getTimestamp("changed_at").getTime(), "last metadata change");
                    assertEquals(lastSeenAt, row.getTimestamp("last_seen_at").getTime(), "last observation");
                    assertTrue(row.getBoolean("available"));
                    return true;
                }, NC_KEY);
    }

    @ParameterizedTest @CsvSource({"90000,false","89999,true"})
    void waitsForJobLockBeforeNowCoderWriteback(long elapsed,boolean live) throws Exception {
        AtomicBoolean metadataWritten=new AtomicBoolean();
        Map<String,Object> lease=prepareNowCoder(metadataWritten),before=snapshot();
        NowCoderStatementClient.Statement update=new NowCoderStatementClient.Statement("8","6",content(NEW_TITLE));
        FetchLeaseLockTestSupport.whileJobLocked(jdbc,admin,"remote_problem_import",
                ()->statements.applyNowCoder(lease,update),()->clock+=elapsed);
        if(live){assertTrue(metadataWritten.get());assertPublished();}
        else{
            assertFalse(metadataWritten.get());assertEquals(before,snapshot());
            Map<String,Object> fresh=statements.claim();assertNotNull(fresh);
            assertNotEquals(lease.get("lease_token"),fresh.get("lease_token"));
            statements.applyNowCoder(fresh,update);assertTrue(metadataWritten.get());assertPublished();
        }
    }

    @Test void rollsBackDiscoveredMetadataWhenStatementValidationFails(){
        AtomicBoolean metadataWritten=new AtomicBoolean();
        Map<String,Object> lease=prepareNowCoder(metadataWritten),before=snapshot();
        Content invalid=content(NEW_TITLE);invalid.setTimeLimit(0);
        ObjectiveException failure=assertThrows(ObjectiveException.class,()->
                statements.applyNowCoder(lease,new NowCoderStatementClient.Statement("8","6",invalid)));
        assertEquals(400,failure.getStatus());
        assertTrue(metadataWritten.get(),"Observe the staged catalog change before the outer transaction rolls back");
        assertEquals(before,snapshot());
        statements.applyNowCoder(lease,new NowCoderStatementClient.Statement("8","6",content(NEW_TITLE)));
        assertPublished();
    }
}
