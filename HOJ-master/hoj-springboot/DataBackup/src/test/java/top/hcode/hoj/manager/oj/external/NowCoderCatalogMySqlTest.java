package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only a uniquely named disposable schema; never claims the application catalogue job. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class NowCoderCatalogMySqlTest {
    NowCoderStatementClient.Page page(int n,int total,long highest){List<NowCoderStatementClient.Entry> entries=new ArrayList<>();for(int i=0;i<Math.min(50,total-(n-1)*50);i++)entries.add(new NowCoderStatementClient.Entry(String.valueOf(highest-(n-1)*50-i),"题目 "+i,1200));return new NowCoderStatementClient.Page(n,total,(total+49)/50,entries);}
    @Test void currentReadsStagePublicationAndRemovalAreAtomicUnderRealMySql() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"),schema="hoj_nc_catalog_it_"+UUID.randomUUID().toString().replace("-","");JdbcTemplate administration=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password));
        long original=administration.queryForObject("SELECT COUNT(*) FROM problem",Long.class);administration.execute("CREATE DATABASE "+schema);
        try{
            DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);JdbcTemplate jdbc=new JdbcTemplate(ds);for(String file:Arrays.asList("remote-sources.sql","nowcoder-sources.sql"))try(Connection connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource(file));}
            assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));AtomicLong clock=new AtomicLong(System.currentTimeMillis());jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name='NOWCODER_PROBLEMS'",new Timestamp(clock.get()));NowCoderCatalogManager manager=new NowCoderCatalogManager(){@Override protected long now(){return clock.get();}};ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(ds));
            ExecutorService workers=Executors.newFixedThreadPool(2);try{
                CountDownLatch start=new CountDownLatch(1);Callable<Map<String,Object>> claim=()->{start.await();return manager.claim();};Future<Map<String,Object>> a=workers.submit(claim),b=workers.submit(claim);start.countDown();Map<String,Object> first=a.get(5,TimeUnit.SECONDS),second=b.get(5,TimeUnit.SECONDS);assertEquals(1,(first==null?0:1)+(second==null?0:1));Map<String,Object> old=first==null?second:first;
                clock.addAndGet(90001);Map<String,Object> current=manager.claim();assertNotEquals(old.get("lease_token"),current.get("lease_token"));manager.apply(old,page(1,51,1000));assertEquals(0L,manager.status().get("total"));manager.apply(current,page(1,51,1000));assertEquals(50L,manager.status().get("total"));
                clock.addAndGet(3001);manager.apply(manager.claim(),page(2,51,1000));assertEquals(50L,manager.status().get("total"));clock.addAndGet(3001);manager.apply(manager.claim(),page(1,51,1000));assertEquals(51L,manager.status().get("total"));assertEquals("IDLE",manager.status().get("state"));
                clock.addAndGet(86400001);manager.apply(manager.claim(),page(1,50,1000));assertEquals(51L,manager.status().get("total"));clock.addAndGet(3001);manager.apply(manager.claim(),page(1,50,1000));assertEquals(50L,manager.status().get("total"));assertEquals(0,jdbc.queryForObject("SELECT available FROM remote_nowcoder_problem WHERE problem_key='nowcoder:acm:950'",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_catalog_stage",Integer.class));
            }finally{workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);}
        }finally{administration.execute("DROP DATABASE "+schema);assertEquals(original,administration.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
}
