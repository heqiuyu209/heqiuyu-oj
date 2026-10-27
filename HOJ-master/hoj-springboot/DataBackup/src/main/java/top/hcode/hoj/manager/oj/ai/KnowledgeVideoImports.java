package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Durable pre-publication drafts. No database lock is held over file IO or ASR. */
@Component
public class KnowledgeVideoImports {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private KnowledgeManager knowledge;
    @Autowired private KnowledgeVideoAssets assets;
    @Autowired private RagServiceClient processor;
    // A checkpoint follows Whisper's native 30-second context; the internal
    // transport accepts at most 60 seconds for independently bounded callers.
    private static final long STEP=30000L;
    private static final Set<String> ERRORS=new HashSet<>(Arrays.asList("VIDEO_NO_AUDIO","VIDEO_UNSUPPORTED_CODEC","VIDEO_DURATION_LIMIT",
            "VIDEO_RESOURCE_LIMIT","VIDEO_MODEL_CHANGED","VIDEO_SOURCE_CHANGED","VIDEO_ASSET_MISSING","VIDEO_RUNTIME_UNAVAILABLE",
            "VIDEO_ASR_UNAVAILABLE","VIDEO_ASSETS_UNAVAILABLE","VIDEO_INVALID","VIDEO_INVALID_SLICE","VIDEO_ASR_FAILED",
            "VIDEO_SERVICE_UNAVAILABLE","INVALID_VIDEO_RESULT","VIDEO_TRANSCRIPT_LIMIT"));
    private volatile boolean stopping;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"knowledge-video-worker");thread.setDaemon(true);return thread;});
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->{try{if(!stopping)processOne();}catch(Exception ignored){/* Durable lease remains; never log filenames, speech or service credentials. */}},10000,1000,TimeUnit.MILLISECONDS);}
    @PreDestroy public void close(){stopping=true;worker.shutdownNow();}
    protected long now(){return System.currentTimeMillis();}
    private Timestamp time(){return new Timestamp(now());}
    private long number(Object value){return ((Number)value).longValue();}
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(transactions).execute(status->action.get());}
    private void id(String value){if(value==null||!value.matches("[a-f0-9]{32}"))throw new ObjectiveException(400,"转写任务标识无效");}
    private Map<String,Object> row(String id,boolean lock){id(id);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM knowledge_video_import WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw new ObjectiveException(404,"视频转写任务不存在");return rows.get(0);}
    private boolean actualAdmin(String uid){
        // Current reads, including REPEATABLE READ callers that already have
        // a snapshot. Lock order is task -> user for worker completions.
        return !jdbc.queryForList("SELECT u.uuid FROM user_info u JOIN user_role ur ON ur.uid=u.uuid JOIN role r ON r.id=ur.role_id WHERE u.uuid=? AND u.status=0 AND r.role IN ('root','admin') FOR UPDATE",uid).isEmpty();
    }
    private void requireAdmin(String uid){if(!actualAdmin(uid))throw new ObjectiveException(403,"当前账号不能维护视频资料");}
    String currentAdmin(){String actor=knowledge.admin();requireAdmin(actor);return actor;}
    Map<String,Object> preview(String id){currentAdmin();Map<String,Object> value=row(id,false);if(!"READY".equals(value.get("state")))throw new ObjectiveException(409,"请等待转写完成；已保存原件请从资料版本播放");return source(value);}
    private void revision(Map<String,Object> row,Long expected){if(expected==null||expected!=number(row.get("revision")))throw new ObjectiveException(409,"转写任务已更新，请刷新后重试");}
    private String encoded(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new ObjectiveException(400,"视频转写数据无效");}}
    private List<KnowledgeVideoDTO.Speech> speech(String value){try{return json.readValue(value,new TypeReference<List<KnowledgeVideoDTO.Speech>>(){});}catch(Exception error){throw new ObjectiveException(503,"转写草稿不可用，请重新转写");}}
    private VideoTranscript timeline(Map<String,Object> row,List<KnowledgeVideoDTO.Speech> segments){Map<String,Object> body=new LinkedHashMap<>();body.put("schema",1);body.put("durationMs",row.get("duration_ms"));body.put("segments",segments);try{return VideoTranscript.parse(encoded(body));}catch(IllegalArgumentException error){throw new VideoServiceFailure(400,"转写正文超过限制或时间轴无效，请拆分视频","VIDEO_TRANSCRIPT_LIMIT");}}
    private void global(){jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all' FOR UPDATE",Integer.class);}
    private void release(Map<String,Object> row){
        int changed=jdbc.update("UPDATE knowledge_video_quota SET active_count=active_count-1 WHERE owner IN ('_all',?) AND active_count>0",row.get("requested_by"));
        if(changed!=2)throw new ObjectiveException(503,"转写任务容量记录异常，请联系管理员");
    }
    private Map<String,Object> view(Map<String,Object> row,boolean content){
        Map<String,Object> result=new LinkedHashMap<>();for(String field:Arrays.asList("id","title","visibility","state","stage","attempts","revision"))result.put(field,row.get(field));
        result.put("sourceName",row.get("source_name"));result.put("byteLength",row.get("byte_length"));result.put("durationMs",row.get("duration_ms"));result.put("processedMs",row.get("next_ms"));
        result.put("language",row.get("language"));result.put("targetDocumentId",row.get("target_document_id"));result.put("expectedRevision",row.get("expected_revision"));result.put("lastError",row.get("last_error"));
        result.put("documentId",row.get("result_document_id"));result.put("version",row.get("result_version"));result.put("updatedAt",row.get("updated_at"));
        if(content&&Arrays.asList("READY","SAVED").contains(row.get("state")))result.put("content",timeline(row,speech((String)row.get("segments"))).canonical());
        return result;
    }
    public Map<String,Object> list(){currentAdmin();Map<String,Object> result=new LinkedHashMap<>();result.put("configured",assets.configured()&&processor.configured());
        if(!assets.configured()){result.put("items",Collections.emptyList());return result;}
        // All at most 12 unfinished drafts stay reachable even after many saved
        // imports. Listing metadata must not repeatedly load large transcripts.
        List<Map<String,Object>> values=new ArrayList<>();for(Map<String,Object> row:jdbc.queryForList("SELECT id,title,visibility,state,stage,attempts,revision,source_name,byte_length,duration_ms,next_ms,language,target_document_id,expected_revision,last_error,result_document_id,result_version,updated_at FROM knowledge_video_import WHERE state<>'CANCELLED' ORDER BY CASE WHEN state='SAVED' THEN 1 ELSE 0 END,updated_at DESC,id LIMIT 40"))values.add(view(row,false));result.put("items",values);return result;}
    public Map<String,Object> detail(String id){currentAdmin();return view(row(id,false),true);}

    public Map<String,Object> upload(String target,KnowledgeDTO.Upload metadata,String name,String language,InputStream input,long length){
        String actor=currentAdmin();knowledge.validateMetadata(metadata);KnowledgeVideoAssets.sourceName(name);
        if(!assets.configured())throw new ObjectiveException(503,"本地视频原件存储尚未配置");
        if(!Arrays.asList("auto","zh","en").contains(language))throw new ObjectiveException(400,"请选择自动识别、中文或英文");
        if(target!=null){Map<String,Object> document=knowledge.detail(target);if(!"ACTIVE".equals(document.get("state")))throw new ObjectiveException(404,"目标资料已撤回");if(metadata.getExpectedRevision()==null||metadata.getExpectedRevision()!=number(document.get("revision")))throw new ObjectiveException(409,"目标资料已更新，请刷新后重试");}
        try(KnowledgeVideoAssets.Staged source=assets.stage(input,length)){
            KnowledgeVideoDTO.Asset ref=new KnowledgeVideoDTO.Asset();ref.setAssetId(source.assetId);ref.setSourceHash(source.sourceHash);ref.setByteLength(source.byteLength);
            Map<String,Object> saved;
            try{saved=register(actor,target,metadata,name,language,ref);}
            catch(ObjectiveException knownRejection){throw knownRejection;}
            catch(RuntimeException uncertain){source.retainAfterUncertainCommit();throw new ObjectiveException(503,"视频登记结果暂时无法确认，请使用原请求重试");}
            if(Boolean.TRUE.equals(saved.remove("newSource")))source.adopt();return saved;
        }
    }
    /** Package-only seam between the private file receiver and durable queue. */
    Map<String,Object> register(String actor,String target,KnowledgeDTO.Upload metadata,String name,String language,KnowledgeVideoDTO.Asset source){
        return tx(()->{
            global();
            List<Map<String,Object>> duplicate=jdbc.queryForList("SELECT * FROM knowledge_video_import WHERE requested_by=? AND request_id=? FOR UPDATE",actor,metadata.getRequestId());
            requireAdmin(actor);String hash=RagServiceClient.hash(encoded(Arrays.asList(target,metadata.getTitle(),metadata.getVisibility(),name,source.getSourceHash(),source.getByteLength(),language)));
            if(!duplicate.isEmpty()){if(!hash.equals(duplicate.get(0).get("input_hash")))throw new ObjectiveException(409,"请求标识已用于其他视频内容");Map<String,Object> result=view(duplicate.get(0),true);result.put("newSource",false);return result;}
            jdbc.update("INSERT INTO knowledge_video_quota(owner,active_count) VALUES(?,0) ON DUPLICATE KEY UPDATE owner=VALUES(owner)",actor);
            int total=jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all' FOR UPDATE",Integer.class),own=jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner=? FOR UPDATE",Integer.class,actor);
            if(total>=12||own>=4)throw new ObjectiveException(429,"待处理视频较多，请先保存或取消已有草稿");
            jdbc.update("UPDATE knowledge_video_quota SET active_count=active_count+1 WHERE owner IN ('_all',?)",actor);
            String id=UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT INTO knowledge_video_import(id,requested_by,processing_by,request_id,input_hash,title,visibility,target_document_id,expected_revision,source_name,asset_id,source_hash,byte_length,language,state,stage,segments,next_run_at,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED','PROBE','[]',?,1,?,?)",
                    id,actor,actor,metadata.getRequestId(),hash,metadata.getTitle(),metadata.getVisibility(),target,metadata.getExpectedRevision(),name,source.getAssetId(),source.getSourceHash(),source.getByteLength(),language,time(),time(),time());
            Map<String,Object> result=view(row(id,false),false);result.put("newSource",true);return result;
        });
    }
    public Map<String,Object> claim(){return tx(()->{
        List<String> ids=jdbc.queryForList("SELECT id FROM knowledge_video_import WHERE (state='QUEUED' AND next_run_at<=?) OR (state='RUNNING' AND lease_until<=?) ORDER BY next_run_at,id LIMIT 8",String.class,time(),time());
        for(String id:ids){String token=UUID.randomUUID().toString();int won=jdbc.update("UPDATE knowledge_video_import SET state='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,stage_attempts=stage_attempts+1,revision=revision+1,updated_at=? WHERE id=? AND ((state='QUEUED' AND next_run_at<=?) OR (state='RUNNING' AND lease_until<=?))",token,new Timestamp(now()+180000),time(),id,time(),time());if(won==1)return row(id,false);}return null;});}
    private Map<String,Object> leased(Map<String,Object> job){Map<String,Object> current=row((String)job.get("id"),true);if(!"RUNNING".equals(current.get("state"))||!Objects.equals(current.get("lease_token"),job.get("lease_token"))||current.get("lease_until")==null||((Timestamp)current.get("lease_until")).getTime()<=now())return null;return current;}
    private void finish(Map<String,Object> row,String state,String code){jdbc.update("UPDATE knowledge_video_import SET state=?,lease_token=NULL,lease_until=NULL,last_error=?,revision=revision+1,updated_at=? WHERE id=?",state,code,time(),row.get("id"));}
    private boolean owner(Map<String,Object> row){if(actualAdmin((String)row.get("processing_by")))return true;finish(row,"FAILED","ACCESS_REVOKED");return false;}
    private void sameSource(Map<String,Object> current,KnowledgeVideoDTO.Asset result){
        if(result==null||!Objects.equals(current.get("asset_id"),result.getAssetId())||!Objects.equals(current.get("source_hash"),result.getSourceHash())
                ||result.getByteLength()==null||number(current.get("byte_length"))!=result.getByteLength())throw new VideoServiceFailure(502,"视频处理回执校验失败，请重试","INVALID_VIDEO_RESULT");
    }
    void probed(Map<String,Object> job,KnowledgeVideoDTO.Probe result){tx(()->{Map<String,Object> current=leased(job);if(current==null||!owner(current))return null;
        sameSource(current,result);if(!"PROBE".equals(current.get("stage"))||result.getDurationMs()==null||result.getDurationMs()<1||result.getDurationMs()>VideoTranscript.MAX_DURATION)throw new VideoServiceFailure(502,"视频处理回执校验失败，请重试","INVALID_VIDEO_RESULT");
        jdbc.update("UPDATE knowledge_video_import SET state='QUEUED',stage='TRANSCRIBE',probe_json=?,duration_ms=?,stage_attempts=0,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL,revision=revision+1,updated_at=? WHERE id=?",encoded(result),result.getDurationMs(),time(),time(),current.get("id"));return null;});}
    void transcribed(Map<String,Object> job,KnowledgeVideoDTO.Transcribed result){tx(()->{
        Map<String,Object> current=leased(job);if(current==null||!owner(current))return null;
        sameSource(current,result);
        long start=number(current.get("next_ms")),duration=number(current.get("duration_ms"));
        if(!"TRANSCRIBE".equals(current.get("stage"))||result.getDurationMs()==null||duration!=result.getDurationMs()||result.getStartMs()==null||start!=result.getStartMs()
                ||result.getEndMs()==null||Math.min(duration,start+STEP)!=result.getEndMs()||result.getSegments()==null||result.getSegments().size()>200
                ||result.getModelHash()==null||!result.getModelHash().matches("[a-f0-9]{64}")||!"faster-whisper-cpu-int8-v1".equals(result.getEngine()))throw new VideoServiceFailure(502,"视频处理回执校验失败，请重试","INVALID_VIDEO_RESULT");
        if(current.get("model_hash")!=null&&!current.get("model_hash").equals(result.getModelHash())||current.get("engine")!=null&&!current.get("engine").equals(result.getEngine()))throw new VideoServiceFailure(409,"转写来源或模型已改变","VIDEO_MODEL_CHANGED");
        for(KnowledgeVideoDTO.Speech value:result.getSegments())if(value==null||value.getStartMs()==null||value.getEndMs()==null||value.getStartMs()<start||value.getEndMs()>result.getEndMs())throw new VideoServiceFailure(502,"视频处理回执校验失败，请重试","INVALID_VIDEO_RESULT");
        List<KnowledgeVideoDTO.Speech> segments=speech((String)current.get("segments"));segments.addAll(result.getSegments());
        if(!segments.isEmpty())timeline(current,segments);
        boolean complete=result.getEndMs().equals(result.getDurationMs());String state=complete?segments.isEmpty()?"FAILED":"READY":"QUEUED";
        jdbc.update("UPDATE knowledge_video_import SET state=?,next_ms=?,segments=?,model_hash=?,engine=?,stage_attempts=0,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error=?,revision=revision+1,updated_at=? WHERE id=?",state,result.getEndMs(),encoded(segments),result.getModelHash(),result.getEngine(),time(),complete&&segments.isEmpty()?"VIDEO_NO_SPEECH":null,time(),current.get("id"));return null;
    });}
    void failed(Map<String,Object> job,String code,boolean retryable){final String safe=ERRORS.contains(code)?code:"VIDEO_SERVICE_UNAVAILABLE";tx(()->{Map<String,Object> current=leased(job);if(current==null)return null;
        boolean retry=retryable&&number(current.get("stage_attempts"))<3;long pause=Math.min(60000,5000L*(1L<<Math.min(3,number(current.get("stage_attempts"))-1)));
        jdbc.update("UPDATE knowledge_video_import SET state=?,lease_token=NULL,lease_until=NULL,last_error=?,next_run_at=?,revision=revision+1,updated_at=? WHERE id=?",retry?"QUEUED":"FAILED",safe,new Timestamp(now()+pause),time(),current.get("id"));return null;});}
    private KnowledgeVideoDTO.Asset asset(Map<String,Object> job){KnowledgeVideoDTO.Asset value=new KnowledgeVideoDTO.Asset();value.setAssetId((String)job.get("asset_id"));value.setSourceHash((String)job.get("source_hash"));value.setByteLength(number(job.get("byte_length")));return value;}
    public boolean processOne(){
        if(stopping||!assets.configured()||!processor.configured())return false;Map<String,Object> job=claim();if(job==null)return false;
        try{
            if(number(job.get("stage_attempts"))>3){failed(job,"VIDEO_SERVICE_UNAVAILABLE",false);return true;}
            boolean allowed=tx(()->{Map<String,Object> current=leased(job);return current!=null&&owner(current);});if(!allowed)return true;
            if("PROBE".equals(job.get("stage"))){KnowledgeVideoDTO.Probe result=processor.probeVideo(asset(job));if(!stopping)probed(job,result);}
            else{KnowledgeVideoDTO.Asset source=asset(job);KnowledgeVideoDTO.Slice part=new KnowledgeVideoDTO.Slice();part.setAssetId(source.getAssetId());part.setSourceHash(source.getSourceHash());part.setByteLength(source.getByteLength());long start=number(job.get("next_ms"));part.setStartMs(start);part.setEndMs(Math.min(number(job.get("duration_ms")),start+STEP));part.setLanguage((String)job.get("language"));part.setExpectedModelHash((String)job.get("model_hash"));KnowledgeVideoDTO.Transcribed result=processor.transcribeVideo(part);if(!stopping)transcribed(job,result);}
        }catch(Exception error){if(!stopping){int status=error instanceof ObjectiveException?((ObjectiveException)error).getStatus():503;String code=error instanceof VideoServiceFailure?((VideoServiceFailure)error).getCode():status==400?"VIDEO_INVALID":status==502?"INVALID_VIDEO_RESULT":"VIDEO_SERVICE_UNAVAILABLE";failed(job,code,status>=500||status==429);}}
        return true;
    }
    public Map<String,Object> cancel(String id,KnowledgeVideoDTO.Change change){String actor=knowledge.admin();Map<String,Object> cancelled=tx(()->{global();Map<String,Object> current=row(id,true);requireAdmin(actor);if("SAVED".equals(current.get("state")))throw new ObjectiveException(409,"该转写已保存为资料，请在资料版本中管理");if(!"CANCELLED".equals(current.get("state"))){revision(current,change==null?null:change.getExpectedRevision());release(current);finish(current,"CANCELLED",null);}return row(id,false);});
        assets.discard((String)cancelled.get("asset_id"));return view(cancelled,false);}
    public Map<String,Object> retry(String id,KnowledgeVideoDTO.Change change){String actor=knowledge.admin();return tx(()->{Map<String,Object> current=row(id,true);requireAdmin(actor);revision(current,change==null?null:change.getExpectedRevision());if(!"FAILED".equals(current.get("state")))throw new ObjectiveException(409,"仅失败的转写任务可以重试");
        if(change.isRestart())jdbc.update("UPDATE knowledge_video_import SET stage='PROBE',probe_json=NULL,duration_ms=NULL,next_ms=0,segments='[]',model_hash=NULL,engine=NULL WHERE id=?",id);
        else if(Arrays.asList("VIDEO_MODEL_CHANGED","VIDEO_NO_SPEECH").contains(current.get("last_error")))throw new ObjectiveException(409,"请重新转写，以保持整份资料的来源一致");
        jdbc.update("UPDATE knowledge_video_import SET state='QUEUED',processing_by=?,stage_attempts=0,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL,revision=revision+1,updated_at=? WHERE id=?",actor,time(),time(),id);return view(row(id,false),false);});}
    private Map<String,Object> source(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>(row);try{KnowledgeVideoDTO.Probe probe=json.readValue((String)row.get("probe_json"),KnowledgeVideoDTO.Probe.class);result.put("mime",probe.getMime());return result;}catch(Exception error){throw new ObjectiveException(503,"视频原件信息不可用");}}
    public Map<String,Object> save(String id,KnowledgeVideoDTO.Save metadata){
        String actor=knowledge.admin();knowledge.validateMetadata(metadata);Map<String,Object> before=row(id,false);
        if(!Arrays.asList("READY","SAVED").contains(before.get("state")))throw new ObjectiveException(409,"请等待视频转写完成后再审阅保存");
        VideoTranscript transcript;try{transcript=metadata.isReviewedVideo()?VideoTranscript.parse(metadata.getContent()):timeline(before,speech((String)before.get("segments")));}catch(IllegalArgumentException error){throw new ObjectiveException(400,"请保留有效的原视频时间轴，校订正文最多 20 万字");}
        KnowledgeVideoUpload upload=new KnowledgeVideoUpload(metadata,source(before),transcript,metadata.isReviewedVideo());
        try(FileChannel ignored=assets.open(upload.assetId,upload.sourceHash,upload.byteLength)){}catch(java.io.IOException error){throw new ObjectiveException(503,"视频原件校验失败");}
        String target=metadata.isAsNew()?null:(String)before.get("target_document_id");String command=RagServiceClient.hash(encoded(Arrays.asList(target,knowledge.fingerprint(upload))));
        return tx(()->{global();Map<String,Object> current=row(id,true);requireAdmin(actor);
            if("SAVED".equals(current.get("state"))){if(!command.equals(current.get("saved_input_hash")))throw new ObjectiveException(409,"该转写已保存为其他内容，请直接编辑资料版本");Map<String,Object> result=knowledge.detail((String)current.get("result_document_id"));result.put("requestedVersion",current.get("result_version"));return result;}
            revision(current,metadata.getImportRevision());if(!"READY".equals(current.get("state")))throw new ObjectiveException(409,"转写草稿已改变，请重新读取");
            Map<String,Object> result=target==null?knowledge.create(upload):knowledge.upload(target,upload);int version=((Number)(result.get("requestedVersion")==null?result.get("latestVersion"):result.get("requestedVersion"))).intValue();
            release(current);jdbc.update("UPDATE knowledge_video_import SET state='SAVED',saved_input_hash=?,result_document_id=?,result_version=?,revision=revision+1,updated_at=? WHERE id=?",command,result.get("id"),version,time(),id);return result;
        });
    }
}
