package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local MySQL only. Uses a uniquely named disposable schema; never claims the application queue and performs no remote IO. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class StatementRefreshMySqlTest {
    Content content(String title){Content c=new Content();c.setTitle(title);c.setDescription("<p>Source fixture.</p>");c.setInput("");c.setOutput("");c.setHint("");c.setExamples("<input>1</input><output>1</output>");c.setTimeLimit(1000);c.setMemoryLimit(256);return c;}
    @Test void currentReadsFenceDisabledRefreshAndOnlyOneWorkerClaimsTheDailyCheck() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"),schema="hoj_refresh_it_"+UUID.randomUUID().toString().replace("-","");
        JdbcTemplate administration=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password));
        long originalMain=administration.queryForObject("SELECT COUNT(*) FROM problem",Long.class);administration.execute("CREATE DATABASE "+schema);
        try {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
        JdbcTemplate jdbc=new JdbcTemplate(ds);DataSourceTransactionManager tm=new DataSourceTransactionManager(ds);TransactionTemplate tx=new TransactionTemplate(tm);
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,username VARCHAR(100),password VARCHAR(255),status INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id INT) ENGINE=InnoDB");jdbc.execute("CREATE TABLE role(id INT PRIMARY KEY,role VARCHAR(32)) ENGINE=InnoDB");jdbc.update("INSERT INTO role VALUES(1,'admin')");
        jdbc.execute("CREATE TABLE problem(id BIGINT AUTO_INCREMENT PRIMARY KEY,problem_id VARCHAR(50),author VARCHAR(100),title VARCHAR(255),description TEXT,input TEXT,output TEXT,hint TEXT,examples TEXT,time_limit INT,memory_limit INT,rating INT,gmt_modified DATETIME(3),auth INT,is_remote BOOLEAN,is_group BOOLEAN,gid BIGINT) ENGINE=InnoDB");
        try(java.sql.Connection connection=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("remote-sources.sql"));}
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_problem_import",Integer.class));
        String uid=UUID.randomUUID().toString().replace("-",""),name="srefresh_"+uid.substring(0,10),contest="990"+Long.parseLong(uid.substring(0,10),16),key="cf:contest:"+contest+":A",display="CF-"+contest+"A";
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());RemoteStatementManager manager=new RemoteStatementManager(){@Override protected long now(){return clock.get();}@Override protected String uid(){return uid;}@Override protected boolean privileged(){return true;}};
        ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",tm);ReflectionTestUtils.setField(manager,"json",new ObjectMapper());
        ExecutorService workers=Executors.newFixedThreadPool(2);long original=jdbc.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        try{
            jdbc.update("INSERT INTO user_info(uuid,username,password) VALUES(?,?,'synthetic fixture')",uid,name);jdbc.update("INSERT INTO user_role(uid,role_id) SELECT ?,id FROM role WHERE role='admin'",uid);
            jdbc.update("INSERT INTO problem(problem_id,title,author,description,is_remote,auth,is_group) VALUES(?,'Fixture',?,'Library edit',TRUE,2,FALSE)",display,name);
            jdbc.update("INSERT INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,tags_json,content_hash,seen_token,last_seen_at,changed_at) VALUES(?,?,'A','Fixture','[]',?,'synthetic',NOW(3),NOW(3))",key,Long.parseLong(contest),RemoteCatalogManager.hash(key));
            manager.request(key);manager.apply(manager.claim(),content("Original"));clock.addAndGet(86400001);Map<String,Object> stale=manager.claim();assertNotNull(stale);
            CountDownLatch started=new CountDownLatch(1);List<Future<?>> waiting=new ArrayList<>();
            tx.execute(status->{
                jdbc.queryForList("SELECT problem_key FROM remote_catalog_problem WHERE problem_key=? FOR UPDATE",key);
                waiting.add(workers.submit(()->{started.countDown();manager.apply(stale,content("Obsolete"));}));
                try{assertTrue(started.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                manager.refresh(key,false);return null;
            });waiting.get(0).get(5,TimeUnit.SECONDS);manager.failed(stale,"obsolete failure");
            assertEquals("Original",((com.fasterxml.jackson.databind.JsonNode)((Map)manager.adminView(key).get("source")).get("content")).path("title").asText());assertNull(jdbc.queryForObject("SELECT next_run_at FROM remote_problem_import WHERE problem_key=?",Timestamp.class,key));
            manager.refresh(key,true);clock.addAndGet(86400001);CountDownLatch go=new CountDownLatch(1);
            Callable<Map<String,Object>> claim=()->{go.await();return manager.claim();};Future<Map<String,Object>> first=workers.submit(claim),second=workers.submit(claim);go.countDown();
            Map<String,Object> a=first.get(5,TimeUnit.SECONDS),b=second.get(5,TimeUnit.SECONDS);assertEquals(1,(a==null?0:1)+(b==null?0:1));manager.apply(a==null?b:a,content("Original"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=?",Integer.class,key));assertEquals("Library edit",jdbc.queryForObject("SELECT description FROM problem WHERE problem_id=?",String.class,display));
            // Match the previous source exactly, then edit it on another connection while the
            // worker's repeatable-read snapshot still contains that untouched copy.
            jdbc.update("UPDATE problem SET title=?,description=?,input='',output='',hint='',examples=?,time_limit=1000,memory_limit=256,rating=1700 WHERE problem_id=?","Original",content("Original").getDescription(),content("Original").getExamples(),display);
            clock.addAndGet(86400001);Map<String,Object> refresh=manager.claim();assertNotNull(refresh);
            AtomicLong waitingConnection=new AtomicLong();CountDownLatch editingStarted=new CountDownLatch(1);List<Future<?>> editingWork=new ArrayList<>();
            tx.execute(status->{
                jdbc.queryForList("SELECT id FROM problem WHERE problem_id=? FOR UPDATE",display);
                jdbc.update("UPDATE problem SET description='Committed manual edit' WHERE problem_id=?",display);
                editingWork.add(workers.submit(()->tx.execute(workerStatus->{
                    waitingConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));editingStarted.countDown();manager.apply(refresh,content("Changed source"));return null;
                })));
                try{
                    assertTrue(editingStarted.await(3,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean rowLockWaiting=false;
                    while(System.nanoTime()<deadline){
                        rowLockWaiting=administration.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",Integer.class,waitingConnection.get())==1;
                        if(rowLockWaiting)break;Thread.sleep(25);
                    }
                    assertTrue(rowLockWaiting,"The actual MySQL worker must be waiting for the editor's row lock");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return null;
            });editingWork.get(0).get(5,TimeUnit.SECONDS);
            assertEquals("Committed manual edit",jdbc.queryForObject("SELECT description FROM problem WHERE problem_id=?",String.class,display));
            assertEquals("Original",jdbc.queryForObject("SELECT title FROM problem WHERE problem_id=?",String.class,display));
            assertEquals("Changed source",((com.fasterxml.jackson.databind.JsonNode)((Map)manager.adminView(key).get("source")).get("content")).path("title").asText());
            jdbc.update("UPDATE problem SET title='Changed source',description=?,input='',output='',hint='',examples=?,time_limit=1000,memory_limit=256 WHERE problem_id=?",content("Original").getDescription(),content("Original").getExamples(),display);
            clock.addAndGet(86400001);Content latest=content("Latest source");latest.setTimeLimit(2000);latest.setMemoryLimit(512);manager.apply(manager.claim(),latest);
            assertEquals("Latest source",jdbc.queryForObject("SELECT title FROM problem WHERE problem_id=?",String.class,display));
            assertEquals(2000,jdbc.queryForObject("SELECT time_limit FROM problem WHERE problem_id=?",Integer.class,display));assertEquals(512,jdbc.queryForObject("SELECT memory_limit FROM problem WHERE problem_id=?",Integer.class,display));
            assertEquals(1700,jdbc.queryForObject("SELECT rating FROM problem WHERE problem_id=?",Integer.class,display));assertEquals(2,jdbc.queryForObject("SELECT auth FROM problem WHERE problem_id=?",Integer.class,display));
        }finally{
            workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);
            jdbc.update("DELETE FROM remote_statement_current WHERE problem_key=?",key);jdbc.update("DELETE FROM remote_statement_version WHERE problem_key=?",key);jdbc.update("DELETE FROM remote_problem_import WHERE problem_key=?",key);jdbc.update("DELETE FROM remote_catalog_problem WHERE problem_key=?",key);jdbc.update("DELETE FROM problem WHERE problem_id=? AND author=?",display,name);jdbc.update("DELETE FROM user_info WHERE uuid=?",uid);
        }
        assertEquals(original,jdbc.queryForObject("SELECT COUNT(*) FROM problem",Long.class));
        } finally {administration.execute("DROP DATABASE "+schema);assertEquals(originalMain,administration.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
}
