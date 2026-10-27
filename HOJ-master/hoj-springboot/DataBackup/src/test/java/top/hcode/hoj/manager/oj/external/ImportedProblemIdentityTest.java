package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Numeric display IDs cannot prove where the contest/index boundary lies. */
class ImportedProblemIdentityTest {
    JdbcTemplate jdbc;
    ProblemIdentityResolver resolver;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:imported_identity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(100),is_remote BOOLEAN)");
        jdbc.execute("CREATE TABLE remote_problem_import(problem_key VARCHAR(120) PRIMARY KEY,problem_id BIGINT,state VARCHAR(16))");
        resolver=new ProblemIdentityResolver(jdbc);
    }
    @Test void onlyExplicitImportMappingCanResolveAmbiguousNumericDisplayIds() {
        assertEquals("hoj:7",ProblemIdentity.local(7,"CF-92101",true));
        jdbc.update("INSERT INTO problem VALUES(7,'CF-92101',TRUE),(8,'GYM-10000101',TRUE),(9,'CF-92101',FALSE),(10,'CF-92101',TRUE)");
        jdbc.update("INSERT INTO remote_problem_import VALUES('cf:contest:921:01',7,'FAILED'),('cf:contest:100001:01',8,'DONE'),('cf:contest:9210:1',9,'DONE')");
        Map<String,String> result=resolver.resolve(Arrays.asList("hoj:7","hoj:8","hoj:9","hoj:10","cf:contest:921:01"));
        assertEquals("cf:contest:921:01",result.get("hoj:7"));assertEquals("cf:contest:100001:01",result.get("hoj:8"));
        assertEquals("hoj:9",result.get("hoj:9"));assertEquals("hoj:10",result.get("hoj:10"));
        assertEquals("cf:contest:921:01",result.get("cf:contest:921:01"));
    }
    @Test void conflictingOrRenamedMappingsRemainDistinctEvenIfTitlesWouldMatch() {
        jdbc.update("INSERT INTO problem VALUES(7,'CF-92101',TRUE),(8,'CF-92102',TRUE),(9,'CF-92101',TRUE)");
        jdbc.update("INSERT INTO remote_problem_import VALUES('cf:contest:921:01',7,'DONE'),('cf:contest:9210:1',7,'DONE'),('cf:contest:922:01',8,'DONE'),('cf:contest:921:01?x=1',9,'DONE')");
        Map<String,String> result=resolver.resolve(Arrays.asList("hoj:7","hoj:8","hoj:9","hoj:0","hoj:9999999999999999999"));
        result.forEach((key,value)->assertEquals(key,value));
    }
}
