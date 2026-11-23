package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real row waits in disposable schemas; no source requests or application queue claims. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class FetchLeaseMySqlTest extends RemoteSourceManagerTest {
    String schema;JdbcTemplate admin;long original;
    @Override @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base="jdbc:mysql://127.0.0.1:13306/",options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+options,"root",password));
        original=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        schema="hoj_fetch_lease_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        initialize(new DriverManagerDataSource(base+schema+options,"root",password));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
    }
    @AfterEach void cleanup(){
        if(admin!=null && schema!=null){assertTrue(schema.matches("hoj_fetch_lease_it_[0-9a-f]{32}"));admin.execute("DROP DATABASE "+schema);assertEquals(original,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
    @ParameterizedTest
    @CsvSource({"catalog-apply,180000,false","catalog-failed,180000,false",
            "statement-apply,90000,false","statement-failed,90000,false","statement-defer,90000,false",
            "catalog-apply,179999,true","catalog-failed,179999,true",
            "statement-apply,89999,true","statement-failed,89999,true","statement-defer,89999,true"})
    void expiredCallbackAfterJobLock(String action,long elapsed,boolean live) throws Exception {
        Map<String,Object> lease=fetchLease(action);assertNotNull(lease);String table=fetchJobTable(action);
        Map<String,Object> before=fetchJobState(action);
        FetchLeaseLockTestSupport.whileJobLocked(jdbc,admin,table,()->invokeFetchCallback(action,lease),()->clock+=elapsed);
        Map<String,Object> after=fetchJobState(action);
        if(live){assertNotEquals(before,after);assertNull(after.get("lease_token"));}
        else {
            assertEquals(before,after);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));
            Map<String,Object> fresh=action.startsWith("catalog")?catalogue.claim():statements.claim();assertNotNull(fresh);
            assertNotEquals(lease.get("lease_token"),fresh.get("lease_token"));invokeFetchCallback(action,fresh);
            assertNull(fetchJobState(action).get("lease_token"));
        }
    }
}
