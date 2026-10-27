package top.hcode.hoj.manager.oj.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Durable jobs, CAS leases and short transactions. Never holds a row lock over HTTP. */
@Component
public class KnowledgeJobManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RagServiceClient index;
    private volatile boolean stopping;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"knowledge-index-worker");t.setDaemon(true);return t;});
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->{try{if(!stopping)processOne();}catch(Exception ignored){/* Job/lease remains durable; no snippets or internal credentials in logs. */}},10000,3000,TimeUnit.MILLISECONDS);}
    @PreDestroy public void close(){stopping=true;worker.shutdownNow();}
    protected long now(){return System.currentTimeMillis();}
    private Timestamp time(){return new Timestamp(now());}
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(transactions).execute(status->action.get());}
    private long number(Object value){return ((Number)value).longValue();}

    public Map<String,Object> claim(){
        return tx(()->{
            List<Map<String,Object>> candidates=jdbc.queryForList("SELECT j.id,j.operation,v.format FROM knowledge_job j JOIN knowledge_version v ON v.document_id=j.document_id AND v.version=j.version WHERE (j.state='QUEUED' AND j.next_run_at<=?) OR (j.state='RUNNING' AND j.lease_until<=?) ORDER BY j.id LIMIT 8",time(),time());
            for(Map<String,Object> candidate:candidates){long id=number(candidate.get("id"));String token=UUID.randomUUID().toString();
                long lease="INGEST".equals(candidate.get("operation"))&&"pdf".equals(candidate.get("format"))?KnowledgeLimits.PDF_JOB_LEASE_MS:180000;
                // UPDATE performs a current read even under MySQL REPEATABLE READ.
                int won=jdbc.update("UPDATE knowledge_job SET state='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=? WHERE id=? AND ((state='QUEUED' AND next_run_at<=?) OR (state='RUNNING' AND lease_until<=?))",
                        token,new Timestamp(now()+lease),time(),id,time(),time());
                if(won==1)return jdbc.queryForMap("SELECT * FROM knowledge_job WHERE id=? AND lease_token=?",id,token);
            }
            return null;
        });
    }
    private Map<String,Object> leased(Map<String,Object> job){
        // Always lock document before job, matching administrator mutations.
        jdbc.queryForList("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE",job.get("document_id"));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM knowledge_job WHERE id=? FOR UPDATE",job.get("id"));
        if(rows.isEmpty())return null;Map<String,Object> row=rows.get(0);
        if(!"RUNNING".equals(row.get("state"))||!Objects.equals(row.get("lease_token"),job.get("lease_token"))||row.get("lease_until")==null||((Timestamp)row.get("lease_until")).getTime()<=now())return null;
        return row;
    }
    public void succeeded(Map<String,Object> job,int chunks){
        tx(()->{
            Map<String,Object> current=leased(job);if(current==null)return null;
            if("INGEST".equals(current.get("operation"))){
                if(chunks<1||chunks>400)throw new ObjectiveException(502,"入库回执无效");
                List<Map<String,Object>> docs=jdbc.queryForList("SELECT state FROM knowledge_document WHERE id=?",current.get("document_id"));
                if(docs.isEmpty()||!"ACTIVE".equals(docs.get(0).get("state")))return null;
                jdbc.update("UPDATE knowledge_version SET state='INDEXED',chunks=?,indexed_at=? WHERE document_id=? AND version=? AND state<>'DELETED'",chunks,time(),current.get("document_id"),current.get("version"));
            }
            jdbc.update("UPDATE knowledge_job SET state='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=? WHERE id=?",time(),current.get("id"));
            return null;
        });
    }
    public void failed(Map<String,Object> job,String code){
        final String safe=Arrays.asList("INDEX_UNAVAILABLE","VERSION_CONFLICT","INVALID_CONTENT").contains(code)?code:"INDEX_UNAVAILABLE";
        tx(()->{
            Map<String,Object> current=leased(job);if(current==null)return null;
            boolean ingest="INGEST".equals(current.get("operation"));long attempts=number(current.get("attempts"));
            boolean terminal=ingest&&(attempts>=3||!"INDEX_UNAVAILABLE".equals(safe));
            long backoff=Math.min(3600000L,10000L*(1L<<Math.min(9,attempts-1)));
            jdbc.update("UPDATE knowledge_job SET state=?,lease_token=NULL,lease_until=NULL,last_error=?,next_run_at=?,updated_at=? WHERE id=?",terminal?"FAILED":"QUEUED",safe,new Timestamp(now()+backoff),time(),current.get("id"));
            if(ingest)jdbc.update("UPDATE knowledge_version SET state=? WHERE document_id=? AND version=? AND state NOT IN ('INDEXED','DELETED')",terminal?"FAILED":"QUEUED",current.get("document_id"),current.get("version"));
            return null;
        });
    }
    public boolean processOne(){
        if(stopping||!index.configured())return false;
        Map<String,Object> job=claim();if(job==null)return false;
        try{
            Map<String,Object> version=jdbc.queryForMap("SELECT title,format,content_hash,content,state FROM knowledge_version WHERE document_id=? AND version=?",job.get("document_id"),job.get("version"));
            KnowledgeDTO.Ingest dto=new KnowledgeDTO.Ingest();dto.setDocumentId((String)job.get("document_id"));dto.setVersion(((Number)job.get("version")).intValue());dto.setContentHash((String)version.get("content_hash"));
            if("DELETE".equals(job.get("operation"))){index.delete(dto);if(!stopping)succeeded(job,0);}
            else{
                // Revocation can happen after this read; the service tombstone and
                // the fenced completion still prevent a deleted version publishing.
                if("DELETED".equals(version.get("state")))return true;
                dto.setTitle((String)version.get("title"));dto.setFormat((String)version.get("format"));dto.setContent((String)version.get("content"));
                int chunks=index.ingest(dto);if(!stopping)succeeded(job,chunks);
            }
        }catch(Exception error){
            if(!stopping){int status=error instanceof ObjectiveException?((ObjectiveException)error).getStatus():503;failed(job,status==409?"VERSION_CONFLICT":status==400?"INVALID_CONTENT":"INDEX_UNAVAILABLE");}
        }
        return true;
    }
}
