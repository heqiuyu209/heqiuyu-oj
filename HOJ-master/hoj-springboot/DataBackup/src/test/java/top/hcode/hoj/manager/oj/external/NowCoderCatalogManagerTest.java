package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderCatalogManagerTest {
    JdbcTemplate jdbc;NowCoderCatalogManager manager;long clock;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:nc_catalogue;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);jdbc.execute("DROP ALL OBJECTS");
        for(String name:Arrays.asList("remote-sources.sql","nowcoder-sources.sql")){String ddl=StreamUtils.copyToString(new ClassPathResource(name).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","");try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}}
        jdbc.execute("CREATE TABLE problem(id BIGINT AUTO_INCREMENT PRIMARY KEY,problem_id VARCHAR(50),auth INT,is_remote BOOLEAN,is_group BOOLEAN,gid BIGINT)");clock=1800000000000L;manager=new NowCoderCatalogManager(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(ds));
    }
    NowCoderStatementClient.Page page(int n,int total,long highest){List<NowCoderStatementClient.Entry> entries=new ArrayList<>();int size=Math.min(50,total-(n-1)*50);for(int i=0;i<size;i++)entries.add(new NowCoderStatementClient.Entry(String.valueOf(highest-(n-1)*50-i),"题目 "+(highest-(n-1)*50-i),i==0?null:1200));return new NowCoderStatementClient.Page(n,total,(total+49)/50,entries);}
    Map<String,Object> next(){clock+=3001;return manager.claim();}
    @Test void completeScanNeedsUnchangedHeadAndOnlyThenCanRetireOldCatalogRows(){
        Map<String,Object> first=manager.claim();manager.apply(first,page(1,51,1000));assertEquals(50L,manager.status().get("total"));assertEquals("RUNNING",manager.status().get("state"));assertNull(manager.claim());
        manager.apply(next(),page(2,51,1000));assertEquals(50L,manager.status().get("total"));manager.apply(next(),page(1,51,1000));assertEquals(51L,manager.status().get("total"));assertEquals("IDLE",manager.status().get("state"));assertNotNull(manager.status().get("lastFullSuccessAt"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_catalog_stage",Integer.class));
        clock+=86400001;manager.apply(manager.claim(),page(1,50,1000));assertEquals(51L,manager.status().get("total"));manager.apply(next(),page(1,50,1000));assertEquals(50L,manager.status().get("total"));assertEquals(0,jdbc.queryForObject("SELECT available FROM remote_nowcoder_problem WHERE problem_key='nowcoder:acm:950'",Integer.class));
    }
    @Test void movingPaginationRestartsWithoutRemovingCacheAndObsoleteLeasesCannotPublish(){
        Map<String,Object> obsolete=manager.claim();clock+=90001;Map<String,Object> current=manager.claim();manager.apply(obsolete,page(1,51,1000));assertEquals(0L,manager.status().get("total"));manager.apply(current,page(1,51,1000));manager.apply(next(),page(2,52,1001));
        assertEquals("BACKOFF",manager.status().get("state"));assertEquals(50L,manager.status().get("total"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_catalog_stage",Integer.class));clock+=60001;Map<String,Object> restarted=manager.claim();assertEquals(1,((Number)restarted.get("next_page")).intValue());assertNotEquals(current.get("scan_token"),restarted.get("scan_token"));
    }
    @Test void freshUnchangedHourlyHeadAvoidsAnotherFullScanAndBusyGateDoesNotCountAsFailure(){
        manager.apply(manager.claim(),page(1,1,1000));manager.apply(next(),page(1,1,1000));clock+=3600001;Map<String,Object> job=manager.claim();manager.defer(job);assertEquals(0,jdbc.queryForObject("SELECT failures FROM remote_source_job WHERE source_name='NOWCODER_PROBLEMS'",Integer.class));manager.apply(next(),page(1,1,1000));assertEquals("IDLE",manager.status().get("state"));assertEquals(1,jdbc.queryForObject("SELECT revision FROM remote_nowcoder_problem",Integer.class));
    }
    @Test void discoveredContestIsSeparateFromTheNativeProblemAndSurvivesOrdinaryCatalogRemoval(){
        Content c=new Content();c.setTitle("公开比赛题");manager.discovered("nowcoder:contest:11244:A",new NowCoderStatementClient.Statement("8","1000",c));manager.apply(manager.claim(),page(1,1,1000));manager.apply(next(),page(1,1,1000));assertEquals(2L,manager.list(1,null,null,null).get("total"));
        List<Map<String,Object>> rows=(List)manager.list(1,null,null,null).get("records");assertNull(rows.get(0).get("rating"));assertEquals(2L,manager.status().get("total"));assertTrue(rows.stream().anyMatch(r->r.get("displayId").equals("NC-11244-A")));clock+=86400001;manager.apply(manager.claim(),page(1,1,999));manager.apply(next(),page(1,1,999));assertEquals(1,jdbc.queryForObject("SELECT available FROM remote_nowcoder_problem WHERE problem_key='nowcoder:contest:11244:A'",Integer.class));
    }

    @Test void nonMonotonicDisplayIdsAreValidButRepeatedIdsAcrossPagesRestartTheScan(){
        NowCoderStatementClient.Page ordered=page(1,51,1000);List<NowCoderStatementClient.Entry> rows=new ArrayList<>(ordered.entries);rows.set(20,new NowCoderStatementClient.Entry("999999","跳号题目",1200));NowCoderStatementClient.Page mixed=new NowCoderStatementClient.Page(1,51,2,rows);
        manager.apply(manager.claim(),mixed);manager.apply(next(),page(2,51,1000));manager.apply(next(),mixed);assertEquals(51L,manager.status().get("total"));
        clock+=86400001;manager.apply(manager.claim(),mixed);NowCoderStatementClient.Page repeated=new NowCoderStatementClient.Page(2,51,2,Collections.singletonList(rows.get(0)));manager.apply(next(),repeated);assertEquals("BACKOFF",manager.status().get("state"));assertEquals(51L,manager.status().get("total"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_catalog_stage",Integer.class));
    }

    @Test void explicitRetryResumesTheFailedPageAndASuccessfulPageClearsTheOldError(){
        manager.apply(manager.claim(),page(1,51,1000));Map<String,Object> failed=next();manager.failed(failed,"temporary failure");assertEquals("BACKOFF",manager.status().get("state"));String generation=(String)failed.get("scan_token");manager.requestSync();Map<String,Object> retry=manager.claim();assertEquals(2,((Number)retry.get("next_page")).intValue());assertEquals(generation,retry.get("scan_token"));manager.apply(retry,page(2,51,1000));assertNull(manager.status().get("lastError"));assertEquals("RUNNING",manager.status().get("state"));manager.apply(next(),page(1,51,1000));assertEquals(51L,manager.status().get("total"));assertThrows(top.hcode.hoj.common.exception.ObjectiveException.class,manager::requestSync);
    }
}
