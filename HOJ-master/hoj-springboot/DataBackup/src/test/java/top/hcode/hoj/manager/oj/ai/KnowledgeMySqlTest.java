package top.hcode.hoj.manager.oj.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class KnowledgeMySqlTest {
    KnowledgeDTO.Upload upload(String nonce,String content,Long revision){KnowledgeDTO.Upload dto=new KnowledgeDTO.Upload();dto.setRequestId(nonce);dto.setContent(content);dto.setTitle("本地并发资料");dto.setFormat("markdown");dto.setVisibility("PUBLIC");dto.setExpectedRevision(revision);return dto;}
    KnowledgePdfUpload pdf(String nonce, byte[] bytes, Long revision) {
        KnowledgeDTO.PdfText text=new KnowledgeDTO.PdfText();text.setContent("第一页\n\f\n\n\f\n第三页");text.setPages(3);text.setEmptyPages(Collections.singletonList(2));
        return new KnowledgePdfUpload(upload(nonce,"ignored",revision),bytes,"mysql.pdf",text);
    }
    <T>List<T> parallel(ExecutorService workers,IntFunction<T> action)throws Exception{
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);List<Future<T>> futures=new ArrayList<>();
        for(int i=0;i<2;i++){final int slot=i;futures.add(workers.submit(()->{ready.countDown();start.await();return action.apply(slot);}));}
        assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();return Arrays.asList(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
    }
    @Test void realMysqlRepeatableReadPreservesUploadIdempotencyEditsAndLeaseFences()throws Exception{
        String schema="hoj_knowledge_it_"+UUID.randomUUID().toString().replace("-","");String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password));
        long original=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);admin.execute("CREATE DATABASE "+schema);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
            JdbcTemplate jdbc=new JdbcTemplate(source);assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
            jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0)");
            try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("knowledge-base.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("knowledge-base.sql"));}
            AtomicLong clock=new AtomicLong(System.currentTimeMillis());RagServiceClient index=mock(RagServiceClient.class);when(index.configured()).thenReturn(true);when(index.ingest(any())).thenReturn(1);
            KnowledgeManager manager=new KnowledgeManager(){@Override protected String uid(){return "alice";}@Override protected boolean maintainer(){return true;}@Override protected long now(){return clock.get();}};
            KnowledgeJobManager jobs=new KnowledgeJobManager(){@Override protected long now(){return clock.get();}};
            for(Object bean:Arrays.asList(manager,jobs)){ReflectionTestUtils.setField(bean,"jdbc",jdbc);ReflectionTestUtils.setField(bean,"transactions",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(bean,"index",index);}
            ReflectionTestUtils.setField(manager,"access",mock(AiAccessGuard.class));
            try{
                List<Map<String,Object>> creates=parallel(workers,slot->manager.create(upload("mysql_create_nonce","第一版",null)));
                String id=(String)creates.get(0).get("id");assertEquals(id,creates.get(1).get("id"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document",Integer.class));
                List<Integer> edits=parallel(workers,slot->{try{manager.upload(id,upload("mysql_edit_nonce_"+slot,"修改内容 "+slot,1L));return 200;}catch(ObjectiveException e){return e.getStatus();}});Collections.sort(edits);assertEquals(Arrays.asList(200,409),edits);
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version",Integer.class));assertEquals(2L,((Number)manager.detail(id).get("revision")).longValue());
                jdbc.update("UPDATE knowledge_job SET state='DONE' WHERE version=1");List<Map<String,Object>> claims=parallel(workers,slot->jobs.claim());
                assertEquals(1,claims.stream().filter(Objects::nonNull).count());Map<String,Object> old=claims.stream().filter(Objects::nonNull).findFirst().get();
                clock.addAndGet(180001);Map<String,Object> current=jobs.claim();assertNotNull(current);assertNotEquals(old.get("lease_token"),current.get("lease_token"));
                jobs.succeeded(old,1);jobs.failed(old,"INDEX_UNAVAILABLE");assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM knowledge_job WHERE version=2",String.class));jobs.succeeded(current,1);
                assertEquals("INDEXED",jdbc.queryForObject("SELECT state FROM knowledge_version WHERE version=2",String.class));assertNull(manager.detail(id).get("publishedVersion"));
                KnowledgeDTO.Change change=new KnowledgeDTO.Change();change.setVersion(2);change.setExpectedRevision(2L);manager.publish(id,change);
                manager.upload(id,upload("mysql_third_nonce","第三版",3L));Map<String,Object> late=jobs.claim();change.setExpectedRevision(4L);manager.remove(id,change);jobs.succeeded(late,1);
                assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version WHERE state='DELETED'",Integer.class));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_job WHERE operation='DELETE' AND state='QUEUED'",Integer.class));
                assertNull(manager.detail(id).get("publishedVersion"));
                byte[] raw=new byte[20*1024*1024];System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII),0,raw,0,5);
                List<Map<String,Object>> pdfCreates=parallel(workers,slot->manager.create(pdf("mysql_pdf_create",raw,null)));
                String pdfId=(String)pdfCreates.get(0).get("id");assertEquals(pdfId,pdfCreates.get(1).get("id"));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source WHERE document_id=?",Integer.class,pdfId));
                assertArrayEquals(raw,jdbc.queryForObject("SELECT source_data FROM knowledge_source WHERE document_id=? AND version=1",byte[].class,pdfId));
                List<Integer> pdfEdits=parallel(workers,slot->{try{byte[] changed=raw.clone();changed[changed.length-1]=(byte)(slot+1);manager.upload(pdfId,pdf("mysql_pdf_edit_"+slot,changed,1L));return 200;}catch(ObjectiveException e){return e.getStatus();}});
                Collections.sort(pdfEdits);assertEquals(Arrays.asList(200,409),pdfEdits);
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source WHERE document_id=?",Integer.class,pdfId));
                assertArrayEquals(raw,jdbc.queryForObject("SELECT source_data FROM knowledge_source WHERE document_id=? AND version=1",byte[].class,pdfId));
                int sourceCount=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source",Integer.class),versionCount=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version",Integer.class);
                // Isolate the owned PDF v1 job in this disposable schema.
                jdbc.update("UPDATE knowledge_job SET state='DONE' WHERE document_id=? OR (document_id=? AND version=2)",id,pdfId);
                Map<String,Object> pdfLease=jobs.claim();assertNotNull(pdfLease);assertEquals(pdfId,pdfLease.get("document_id"));
                assertTrue(((java.sql.Timestamp)pdfLease.get("lease_until")).getTime()-clock.get()>=KnowledgeLimits.PDF_JOB_LEASE_MS);
                clock.addAndGet(180001);assertNull(jobs.claim()); // Ordinary three-minute expiry must not steal a PDF.
                clock.addAndGet(KnowledgeLimits.PDF_JOB_LEASE_MS-180000);Map<String,Object> replacement=jobs.claim();assertNotNull(replacement);
                assertNotEquals(pdfLease.get("lease_token"),replacement.get("lease_token"));
                jobs.succeeded(pdfLease,1);assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM knowledge_job WHERE id=?",String.class,replacement.get("id")));
                jobs.succeeded(replacement,1);assertEquals("DONE",jdbc.queryForObject("SELECT state FROM knowledge_job WHERE id=?",String.class,replacement.get("id")));
                jdbc.execute("CREATE TRIGGER reject_new_pdf BEFORE INSERT ON knowledge_job FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test queue failure'");
                assertThrows(RuntimeException.class,()->manager.create(pdf("mysql_pdf_rollback",raw,null)));
                assertEquals(sourceCount,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source",Integer.class));assertEquals(versionCount,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version",Integer.class));
            }finally{jobs.close();}
        }finally{workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);assertTrue(schema.matches("hoj_knowledge_it_[a-f0-9]{32}"));admin.execute("DROP DATABASE "+schema);assertEquals(original,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
}
