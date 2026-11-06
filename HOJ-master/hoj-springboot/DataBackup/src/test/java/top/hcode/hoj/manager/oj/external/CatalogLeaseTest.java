package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Lease expiry preserves both public caches and the exact pending scan checkpoint. */
class CatalogLeaseTest {
    JdbcTemplate jdbc;AtCoderCatalogManager at;NowCoderCatalogManager nc;ObjectMapper json=new ObjectMapper();
    AtomicLong clock=new AtomicLong(1800000000000L);
    @BeforeEach void setup() throws Exception {initialize(new DriverManagerDataSource("jdbc:h2:mem:catalog_lease_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));}
    void initialize(DriverManagerDataSource ds) throws Exception {
        jdbc=new JdbcTemplate(ds);
        for(String name:Arrays.asList("external-learning.sql","remote-sources.sql","atcoder-learning.sql","nowcoder-sources.sql")){
            String ddl=StreamUtils.copyToString(new ClassPathResource(name).getInputStream(),StandardCharsets.UTF_8);
            if(ds.getUrl().startsWith("jdbc:h2:"))ddl=ddl.replace(" COLLATE=utf8mb4_unicode_ci","").replace("DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND)","CURRENT_TIMESTAMP(3)");
            try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
        }
        at=new AtCoderCatalogManager(){@Override protected long now(){return clock.get();}};
        nc=new NowCoderCatalogManager(){@Override protected long now(){return clock.get();}};
        for(Object manager:Arrays.asList(at,nc)){ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(ds));}
        at.apply(lease("AT_PROBLEMS_APPLY"),json.readTree("[{\"id\":\"abc001_a\",\"contest_id\":\"abc001\",\"problem_index\":\"A\",\"title\":\"Original task\"}]"));
        at.apply(lease("AT_MODELS_APPLY"),json.readTree("{\"abc001_a\":{\"difficulty\":800,\"is_experimental\":false}}"));
        nc.apply(lease("NC_APPLY"),page(1000,"Original source"));clock.addAndGet(3001);nc.apply(lease("NC_APPLY"),page(1000,"Original source"));
    }
    static Stream<Arguments> callbacks(){return Stream.of("AT_PROBLEMS_APPLY","AT_PROBLEMS_FAILED","AT_MODELS_APPLY","AT_MODELS_FAILED","NC_APPLY","NC_FAILED","NC_DEFER").flatMap(action->Stream.of(false,true).map(live->Arguments.of(action,live)));}
    String source(String action){return action.startsWith("AT_PROBLEMS")?"ATCODER_PROBLEMS":action.startsWith("AT_MODELS")?"ATCODER_MODELS":"NOWCODER_PROBLEMS";}
    Map<String,Object> lease(String action){
        jdbc.update("UPDATE remote_source_job SET next_run_at=NULL WHERE source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS','NOWCODER_PROBLEMS')");
        jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name=?",new Timestamp(clock.get()),source(action));
        Map<String,Object> job=action.startsWith("AT_")?at.claim():nc.claim();assertNotNull(job);assertEquals(source(action),job.get("source_name"));return job;
    }
    long deadline(Map<String,Object> job){return jdbc.queryForObject("SELECT lease_until FROM remote_source_job WHERE source_name=?",Timestamp.class,job.get("source_name")).getTime();}
    NowCoderStatementClient.Page page(long id,String title){return new NowCoderStatementClient.Page(1,1,1,Collections.singletonList(new NowCoderStatementClient.Entry(String.valueOf(id),title,1200)));}
    void invoke(String action,Map<String,Object> job){
        try{
            if("AT_PROBLEMS_APPLY".equals(action))at.apply(job,json.readTree("[{\"id\":\"abc001_b\",\"contest_id\":\"abc001\",\"problem_index\":\"B\",\"title\":\"Changed task\"}]"));
            else if("AT_MODELS_APPLY".equals(action))at.apply(job,json.readTree("{\"abc001_a\":{\"difficulty\":1600,\"is_experimental\":false}}"));
            else if(action.startsWith("AT_"))at.failed(job,"late fixture");
            else if("NC_APPLY".equals(action))nc.apply(job,page(1001,"Changed source"));
            else if("NC_FAILED".equals(action))nc.failed(job,"late fixture");
            else nc.defer(job);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    Map<String,Object> snapshot(String action){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("job",jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name=?",source(action)));
        result.put("tasks",jdbc.queryForList("SELECT * FROM external_atcoder_problem ORDER BY problem_key"));
        result.put("evidence",jdbc.queryForList("SELECT * FROM external_problem ORDER BY problem_key"));
        result.put("catalog",jdbc.queryForList("SELECT * FROM remote_nowcoder_problem ORDER BY problem_key"));
        result.put("scan",jdbc.queryForMap("SELECT * FROM remote_nowcoder_scan WHERE source_name='NOWCODER_PROBLEMS'"));
        result.put("staged",jdbc.queryForList("SELECT * FROM remote_nowcoder_catalog_stage ORDER BY scan_token,native_problem_id"));return result;
    }
    void assertFreshWorkerRecovers(String action,Map<String,Object> old){
        Map<String,Object> fresh=action.startsWith("AT_")?at.claim():nc.claim();assertNotNull(fresh);assertNotEquals(old.get("lease_token"),fresh.get("lease_token"));
        invoke(action,fresh);assertNull(jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name=?",source(action)).get("lease_token"));
    }
    @ParameterizedTest @MethodSource("callbacks")
    void deadlineWithoutReclaimCannotChangePublishedCacheOrCheckpoint(String action,boolean live){
        Map<String,Object> job=lease(action);Map<String,Object> before=snapshot(action);
        clock.set(deadline(job)-(live?1:0));invoke(action,job);
        if(live){assertNotEquals(before,snapshot(action));assertNull(jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name=?",source(action)).get("lease_token"));}
        else{assertEquals(before,snapshot(action));assertFreshWorkerRecovers(action,job);}
    }
}
