package top.hcode.hoj.remote;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.sql.Timestamp;
import java.util.*;

/** Durable non-secret source identity. Locks always follow lane -> task -> provider row. */
public final class NowCoderTaskJournal {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JdbcTemplate jdbc;
    private final PersonalRemoteTaskStore tasks;
    private final TransactionTemplate transaction;
    public NowCoderTaskJournal(JdbcTemplate jdbc,PlatformTransactionManager transactions,PersonalRemoteTaskStore tasks){this.jdbc=jdbc;this.tasks=tasks;this.transaction=new TransactionTemplate(transactions);}
    private static long number(Object value){return ((Number)value).longValue();}
    static void insertContext(JdbcTemplate jdbc,long id,NowCoderJudgeProtocol.Context context){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Source snapshot requires enqueue transaction");
        try{jdbc.update("INSERT INTO personal_remote_nowcoder(submit_id,context_json,context_hash) VALUES(?,?,?)",id,JSON.writeValueAsString(context.snapshot()),context.signature());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Invalid source snapshot");}
    }
    static NowCoderJudgeProtocol.Context context(JdbcTemplate jdbc,long id){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT context_json,context_hash FROM personal_remote_nowcoder WHERE submit_id=?",id);
        if(rows.size()!=1)throw new PersonalRemoteTaskStore.Conflict("Source snapshot is unavailable");Map<String,Object> row=rows.get(0);
        try{
            String source=(String)row.get("context_json");if(source==null || source.length()>4096)throw new IllegalArgumentException();
            JsonNode value=JSON.readTree(source);if(value==null || !value.isObject() || value.size()!=6)throw new IllegalArgumentException();Map<String,String> fields=new LinkedHashMap<>();
            Iterator<Map.Entry<String,JsonNode>> entries=value.fields();while(entries.hasNext()){Map.Entry<String,JsonNode> entry=entries.next();if(!entry.getValue().isTextual())throw new IllegalArgumentException();fields.put(entry.getKey(),entry.getValue().asText());}
            NowCoderJudgeProtocol.Context context=NowCoderJudgeProtocol.Context.from(fields);if(!context.signature().equals(row.get("context_hash")))throw new IllegalArgumentException();return context;
        }catch(Exception e){throw new PersonalRemoteTaskStore.Conflict("Source snapshot is unavailable or changed");}
    }
    public NowCoderJudgeProtocol.Context context(long id){return context(jdbc,id);}
    private Map<String,Object> row(long id,String token){
        Map<String,Object> task=tasks.leased(id,token);if(!"NOWCODER".equals(task.get("platform")) || number(task.get("submit_attempts"))!=1)throw new PersonalRemoteTaskStore.Conflict("Source journal is not a posted NowCoder task");
        return task;
    }
    private Map<String,Object> source(long id){return jdbc.queryForMap("SELECT * FROM personal_remote_nowcoder WHERE submit_id=? FOR UPDATE",id);}
    /** Remembers the JSON acknowledgement for this exact context/code, without confusing job and public IDs. */
    public void acknowledged(long id,String token,NowCoderJudgeProtocol.Acknowledgement reply){
        Objects.requireNonNull(reply);transaction.execute(status->{
            Map<String,Object> task=row(id,token),source=source(id);
            if(!Arrays.asList("SUBMITTING","RECONCILING").contains(task.get("state")) || !reply.contextHash.equals(source.get("context_hash")) || !reply.codeHash.equals(task.get("code_hash")))throw new PersonalRemoteTaskStore.Conflict("Unmatched source acknowledgement");
            context(jdbc,id);
            if(source.get("backend_job_id")!=null && !reply.jobId.equals(source.get("backend_job_id")))throw new PersonalRemoteTaskStore.Conflict("Source job already identified");
            if(reply.submissionId!=null && reply.submissionId<=number(task.get("baseline_id")))throw new PersonalRemoteTaskStore.Conflict("Source submission predates this task");
            jdbc.update("UPDATE personal_remote_nowcoder SET backend_job_id=?,source_submission_id=? WHERE submit_id=?",reply.jobId,reply.submissionId,id);
            jdbc.update("UPDATE personal_remote_task SET state='POLLING',remote_submit_id=?,read_failures=0,error_code=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE submit_id=?",reply.submissionId,tasks.time(),tasks.time(),id);return null;
        });
    }
    /** Commits the confirmed source ID and normal HOJ verdict in one transaction; duplicate leases cannot grade twice. */
    public boolean result(long id,String token,NowCoderJudgeProtocol.Status result,Runnable applyVerdict){
        Objects.requireNonNull(result);Objects.requireNonNull(applyVerdict);return transaction.execute(status->{
            Map<String,Object> task=row(id,token),source=source(id);context(jdbc,id);
            if(!"POLLING".equals(task.get("state")) || !result.jobId.equals(source.get("backend_job_id")) || !result.contextHash.equals(source.get("context_hash")))throw new PersonalRemoteTaskStore.Conflict("Source result belongs to another task");
            if(result.submissionId!=null){
                if(result.submissionId<=number(task.get("baseline_id")) || source.get("source_submission_id")!=null && number(source.get("source_submission_id"))!=result.submissionId)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AMBIGUOUS_RESULT);
                jdbc.update("UPDATE personal_remote_nowcoder SET source_submission_id=? WHERE submit_id=?",result.submissionId,id);jdbc.update("UPDATE personal_remote_task SET remote_submit_id=? WHERE submit_id=?",result.submissionId,id);
            }
            if(result.verdict==null){tasks.waitingForResult(id,token);return false;}
            if(result.submissionId==null)throw new PersonalRemoteTaskStore.Conflict("Final source submission is not identified");
            return tasks.completed(id,token,applyVerdict);
        });
    }
    /** Internal worker-only metadata. Public status deliberately never exposes this row. */
    public String job(long id){return jdbc.queryForObject("SELECT backend_job_id FROM personal_remote_nowcoder WHERE submit_id=?",String.class,id);}
}
