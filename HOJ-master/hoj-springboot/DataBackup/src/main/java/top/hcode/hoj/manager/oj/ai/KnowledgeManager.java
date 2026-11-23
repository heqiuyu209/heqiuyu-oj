package top.hcode.hoj.manager.oj.ai;

import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.utils.ShiroUtils;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Authoritative documents/publication. Every retrieval is authorized again after indexing. */
@Component
public class KnowledgeManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RagServiceClient index;
    @Autowired private AiAccessGuard access;
    @Value("${hoj.knowledge.media-dir:}") private String mediaDirectory="";
    private final Semaphore capacity = new Semaphore(4);

    protected long now() { return System.currentTimeMillis(); }
    protected String uid() { if (ShiroUtils.getProfile() == null) throw new ObjectiveException(401, "请先登录"); return ShiroUtils.getProfile().getUid(); }
    protected boolean maintainer() { return SecurityUtils.getSubject().hasRole("root") || SecurityUtils.getSubject().hasRole("admin"); }
    protected String admin() { String uid = uid(); access.requireActiveUser(uid); if (!maintainer()) throw new ObjectiveException(403, "仅管理员可维护知识库"); return uid; }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private Timestamp time() { return new Timestamp(now()); }
    private int length(String value) { return value.codePointCount(0, value.length()); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private void id(String id) { if (id == null || !id.matches("[a-f0-9]{32}")) throw new ObjectiveException(400, "资料标识无效"); }
    private Map<String,Object> document(String id, boolean lock) {
        id(id); List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM knowledge_document WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new ObjectiveException(404, "资料不存在或已撤回"); return rows.get(0);
    }
    private void active(Map<String,Object> doc) { if (!"ACTIVE".equals(doc.get("state"))) throw new ObjectiveException(404, "资料不存在或已撤回"); }
    private void revision(Map<String,Object> doc, Long expected) { if (expected == null || expected != number(doc.get("revision"))) throw new ObjectiveException(409, "资料已更新，请刷新后重试"); }
    private void visibility(String value) { if (!Arrays.asList("PUBLIC", "ADMIN").contains(value)) throw new ObjectiveException(400, "请选择全站可见或仅管理员可见"); }
    protected void validateMetadata(KnowledgeDTO.Upload dto) {
        if (dto == null || dto.getRequestId() == null || !dto.getRequestId().matches("[A-Za-z0-9_-]{8,64}")) throw new ObjectiveException(400, "上传请求标识无效");
        visibility(dto.getVisibility());
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty() || length(dto.getTitle().trim()) > 200 || dto.getTitle().indexOf('\0') >= 0)
            throw new ObjectiveException(400, "请填写 200 字以内的资料标题");
        dto.setTitle(dto.getTitle().trim());
    }
    private void validate(KnowledgeDTO.Upload dto) {
        validateMetadata(dto);
        boolean pdf = dto instanceof KnowledgePdfUpload && "pdf".equals(dto.getFormat());
        boolean video = dto instanceof KnowledgeVideoUpload && "video".equals(dto.getFormat());
        if (dto.getContent() == null || dto.getContent().trim().isEmpty() || length(dto.getContent()) > (pdf ? KnowledgeLimits.PDF_CHARACTERS : KnowledgeLimits.TEXT_CHARACTERS) || dto.getContent().indexOf('\0') >= 0
                || !("markdown".equals(dto.getFormat()) || "text".equals(dto.getFormat()) || pdf || video))
            throw new ObjectiveException(400, "Markdown / TXT / 视频正文最多 20 万字；PDF 请上传文件，提取文字最多 30 万字");
        dto.setTitle(dto.getTitle().trim()); dto.setContent(dto.getContent().replace("\r\n", "\n").replace('\r', '\n'));
    }
    protected String fingerprint(KnowledgeDTO.Upload dto) {
        // Length prefixes avoid ambiguity; expectedRevision isn't part of content identity.
        StringBuilder value = new StringBuilder();
        List<String> fields;
        if (dto instanceof KnowledgePdfUpload) {
            KnowledgePdfUpload pdf = (KnowledgePdfUpload) dto;
            // Parser output can change after an upgrade. A replay identifies
            // the submitted immutable bytes, never a new extraction result.
            fields = Arrays.asList(dto.getTitle(), "pdf", pdf.sourceName, pdf.sourceHash, dto.getVisibility());
            if (pdf.isOcr()) fields = Arrays.asList(dto.getTitle(), "pdf", pdf.sourceName, pdf.sourceHash, dto.getVisibility(), "OCR");
            if (pdf.isReviewedPdf()) fields = Arrays.asList(dto.getTitle(), "pdf", pdf.sourceName, pdf.sourceHash, dto.getVisibility(), pdf.extractionMode(), RagServiceClient.hash(dto.getContent()));
        } else if(dto instanceof KnowledgeVideoUpload){
            KnowledgeVideoUpload video=(KnowledgeVideoUpload)dto;
            fields=Arrays.asList(dto.getTitle(),"video",video.assetId,video.sourceName,video.sourceHash,video.modelHash,video.engine,video.language,video.mode,RagServiceClient.hash(dto.getContent()),dto.getVisibility());
        } else fields = Arrays.asList(dto.getTitle(), dto.getFormat(), dto.getContent(), dto.getVisibility());
        for (String field : fields) value.append(field.length()).append(':').append(field);
        return RagServiceClient.hash(value.toString());
    }
    private void queue(String id, int version, String operation) {
        jdbc.update("INSERT INTO knowledge_job(document_id,version,operation,state,next_run_at,updated_at) VALUES(?,?,?,'QUEUED',?,?)", id, version, operation, time(), time());
    }
    private void version(String id, int version, String actor, KnowledgeDTO.Upload dto) {
        jdbc.update("INSERT INTO knowledge_version(document_id,version,title,format,content_hash,content,state,created_by,created_at) VALUES(?,?,?,?,?,?,'QUEUED',?,?)",
                id, version, dto.getTitle(), dto.getFormat(), RagServiceClient.hash(dto.getContent()), dto.getContent(), actor, time());
        if (dto instanceof KnowledgePdfUpload) {
            KnowledgePdfUpload pdf = (KnowledgePdfUpload) dto;
            jdbc.update("INSERT INTO knowledge_source(document_id,version,source_name,source_hash,byte_length,pages,empty_pages,source_data,extraction_mode) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, version, pdf.sourceName, pdf.sourceHash, pdf.source.length, pdf.pages, pdf.emptyPagesValue(), pdf.source, pdf.extractionMode());
        }
        if(dto instanceof KnowledgeVideoUpload){
            KnowledgeVideoUpload video=(KnowledgeVideoUpload)dto;
            jdbc.update("INSERT INTO knowledge_video_source(document_id,version,asset_id,source_name,source_hash,byte_length,duration_ms,mime,language,engine,model_hash,extraction_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,version,video.assetId,video.sourceName,video.sourceHash,video.byteLength,video.duration,video.mime,video.language,video.engine,video.modelHash,video.mode);
        }
        queue(id, version, "INGEST");
    }
    private void command(String id, String actor, KnowledgeDTO.Upload dto, int version) {
        jdbc.update("INSERT INTO knowledge_command(document_id,uid,request_id,input_hash,version) VALUES(?,?,?,?,?)", id, actor, dto.getRequestId(), fingerprint(dto), version);
    }
    public Map<String,Object> create(KnowledgeDTO.Upload dto) {
        String actor = admin(); validate(dto);
        return tx(() -> {
            // Serialize idempotency for this administrator before establishing a snapshot.
            List<Map<String,Object>> users = jdbc.queryForList("SELECT uuid,status FROM user_info WHERE uuid=? FOR UPDATE", actor);
            if (users.isEmpty() || number(users.get(0).get("status")) != 0) throw new ObjectiveException(403, "当前账号无法维护资料");
            List<Map<String,Object>> duplicates = jdbc.queryForList("SELECT id,creation_hash FROM knowledge_document WHERE created_by=? AND request_id=?", actor, dto.getRequestId());
            if (!duplicates.isEmpty()) {
                if (!fingerprint(dto).equals(duplicates.get(0).get("creation_hash"))) throw new ObjectiveException(409, "请求标识已用于其他资料内容");
                return detail((String) duplicates.get(0).get("id"));
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            jdbc.update("INSERT INTO knowledge_document(id,title,visibility,state,latest_version,revision,created_by,request_id,creation_hash,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,1,?,?,?,?,?)",
                    id, dto.getTitle(), dto.getVisibility(), actor, dto.getRequestId(), fingerprint(dto), time(), time());
            version(id, 1, actor, dto); command(id, actor, dto, 1); return detail(id);
        });
    }
    public Map<String,Object> upload(String id, KnowledgeDTO.Upload dto) {
        String actor = admin(); validate(dto);
        return tx(() -> {
            Map<String,Object> doc = document(id, true); active(doc);
            List<Map<String,Object>> duplicates = jdbc.queryForList("SELECT input_hash,version FROM knowledge_command WHERE document_id=? AND uid=? AND request_id=?", id, actor, dto.getRequestId());
            if (!duplicates.isEmpty()) {
                if (!fingerprint(dto).equals(duplicates.get(0).get("input_hash"))) throw new ObjectiveException(409, "请求标识已用于其他资料内容");
                Map<String,Object> result = detail(id); result.put("requestedVersion", duplicates.get(0).get("version")); return result;
            }
            revision(doc, dto.getExpectedRevision()); int latest = ((Number) doc.get("latest_version")).intValue();
            Map<String,Object> previous = jdbc.queryForMap("SELECT title,format,content_hash FROM knowledge_version WHERE document_id=? AND version=?", id, latest);
            boolean same = dto.getTitle().equals(previous.get("title")) && dto.getFormat().equals(previous.get("format")) && RagServiceClient.hash(dto.getContent()).equals(previous.get("content_hash"));
            if (dto instanceof KnowledgePdfUpload) {
                KnowledgePdfUpload pdf = (KnowledgePdfUpload) dto;
                List<Map<String,Object>> sources = jdbc.queryForList("SELECT source_name,source_hash,extraction_mode FROM knowledge_source WHERE document_id=? AND version=?", id, latest);
                same = same && !sources.isEmpty() && pdf.sourceName.equals(sources.get(0).get("source_name")) && pdf.sourceHash.equals(sources.get(0).get("source_hash"));
                same = same && pdf.extractionMode().equals(sources.get(0).get("extraction_mode"));
            }
            if(dto instanceof KnowledgeVideoUpload){
                KnowledgeVideoUpload video=(KnowledgeVideoUpload)dto;
                List<Map<String,Object>> sources=jdbc.queryForList("SELECT asset_id,source_name,source_hash,model_hash,extraction_mode FROM knowledge_video_source WHERE document_id=? AND version=?",id,latest);
                same=same&&!sources.isEmpty()&&video.assetId.equals(sources.get(0).get("asset_id"))&&video.sourceName.equals(sources.get(0).get("source_name"))
                        &&video.sourceHash.equals(sources.get(0).get("source_hash"))&&video.modelHash.equals(sources.get(0).get("model_hash"))&&video.mode.equals(sources.get(0).get("extraction_mode"));
            }
            int target = latest;
            if (!same) {
                if (latest == Integer.MAX_VALUE) throw new ObjectiveException(400, "资料版本数已达上限");
                target++; version(id, target, actor, dto);
            }
            if (!same || !dto.getVisibility().equals(doc.get("visibility")))
                jdbc.update("UPDATE knowledge_document SET title=?,visibility=?,latest_version=?,revision=revision+1,updated_at=? WHERE id=?", dto.getTitle(), dto.getVisibility(), target, time(), id);
            command(id, actor, dto, target); Map<String,Object> result = detail(id); result.put("requestedVersion", target); return result;
        });
    }
    public Map<String,Object> publish(String id, KnowledgeDTO.Change dto) {
        admin(); if (dto == null || dto.getVersion() == null || dto.getVersion() < 1) throw new ObjectiveException(400, "请选择待发布的版本");
        return tx(() -> {
            Map<String,Object> doc = document(id, true); active(doc); revision(doc, dto.getExpectedRevision());
            List<Map<String,Object>> versions = jdbc.queryForList("SELECT state FROM knowledge_version WHERE document_id=? AND version=?", id, dto.getVersion());
            if (versions.isEmpty() || !"INDEXED".equals(versions.get(0).get("state"))) throw new ObjectiveException(409, "该版本尚未完成入库，不能发布");
            jdbc.update("UPDATE knowledge_document SET published_version=?,revision=revision+1,updated_at=? WHERE id=?", dto.getVersion(), time(), id); return detail(id);
        });
    }
    public Map<String,Object> changeVisibility(String id, KnowledgeDTO.Change dto) {
        admin(); if (dto == null) throw new ObjectiveException(400, "请选择可见范围"); visibility(dto.getVisibility());
        return tx(() -> { Map<String,Object> doc = document(id, true); active(doc); revision(doc, dto.getExpectedRevision());
            jdbc.update("UPDATE knowledge_document SET visibility=?,revision=revision+1,updated_at=? WHERE id=?", dto.getVisibility(), time(), id); return detail(id); });
    }
    public Map<String,Object> unpublish(String id, KnowledgeDTO.Change dto) {
        admin(); return tx(() -> { Map<String,Object> doc = document(id, true); active(doc); revision(doc, dto == null ? null : dto.getExpectedRevision());
            jdbc.update("UPDATE knowledge_document SET published_version=NULL,revision=revision+1,updated_at=? WHERE id=?", time(), id); return detail(id); });
    }
    public Map<String,Object> remove(String id, KnowledgeDTO.Change dto) {
        admin(); return tx(() -> { Map<String,Object> doc = document(id, true); active(doc); revision(doc, dto == null ? null : dto.getExpectedRevision());
            jdbc.update("UPDATE knowledge_document SET state='DELETED',published_version=NULL,revision=revision+1,updated_at=? WHERE id=?", time(), id);
            jdbc.update("UPDATE knowledge_version SET state='DELETED' WHERE document_id=?", id);
            jdbc.update("UPDATE knowledge_job SET state='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=? WHERE document_id=? AND operation='INGEST' AND state IN ('QUEUED','RUNNING','FAILED')", time(), id);
            for (Integer v : jdbc.queryForList("SELECT version FROM knowledge_version WHERE document_id=?", Integer.class, id)) queue(id, v, "DELETE");
            return detail(id); });
    }
    public Map<String,Object> retry(String id, int version, KnowledgeDTO.Change dto) {
        admin(); return tx(() -> { Map<String,Object> doc = document(id, true); active(doc); revision(doc, dto == null ? null : dto.getExpectedRevision());
            List<Map<String,Object>> jobs = jdbc.queryForList("SELECT state,lease_until FROM knowledge_job WHERE document_id=? AND version=? AND operation='INGEST' FOR UPDATE", id, version);
            if (jobs.isEmpty()) throw new ObjectiveException(404, "资料版本不存在");
            Map<String,Object> job = jobs.get(0);
            if ("RUNNING".equals(job.get("state")) && job.get("lease_until") != null && ((Timestamp)job.get("lease_until")).getTime() > now()) throw new ObjectiveException(409, "该版本正在入库，请稍后再试");
            jdbc.update("UPDATE knowledge_job SET state='QUEUED',attempts=0,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=? WHERE document_id=? AND version=? AND operation='INGEST'", time(), time(), id, version);
            jdbc.update("UPDATE knowledge_version SET state='QUEUED' WHERE document_id=? AND version=? AND state<>'INDEXED'", id, version);
            jdbc.update("UPDATE knowledge_document SET revision=revision+1,updated_at=? WHERE id=?", time(), id); return detail(id); });
    }
    public Map<String,Object> list(int page, String keyword) {
        admin(); if (page < 1 || page > 100000 || keyword != null && length(keyword) > 200) throw new ObjectiveException(400, "分页或搜索条件无效");
        String pattern = "%" + (keyword == null ? "" : keyword.trim()).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Map<String,Object> result = new LinkedHashMap<>(); result.put("indexConfigured", index.configured());result.put("videoConfigured",mediaDirectory!=null&&!mediaDirectory.trim().isEmpty());
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE state='ACTIVE' AND title LIKE ? ESCAPE '!'", Long.class, pattern));
        result.put("items", jdbc.queryForList("SELECT id,title,visibility,state,latest_version AS latestVersion,published_version AS publishedVersion,revision,updated_at AS updatedAt FROM knowledge_document WHERE state='ACTIVE' AND title LIKE ? ESCAPE '!' ORDER BY updated_at DESC,id LIMIT 20 OFFSET ?", pattern, (page-1)*20)); return result;
    }
    public Map<String,Object> detail(String id) {
        admin(); Map<String,Object> doc = document(id, false); Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", id); for (String key : Arrays.asList("title", "visibility", "state", "revision")) result.put(key, doc.get(key));
        result.put("latestVersion", doc.get("latest_version")); result.put("publishedVersion", doc.get("published_version")); result.put("indexConfigured", index.configured());
        result.put("versions", jdbc.queryForList("SELECT v.version,v.title,v.format,v.content_hash AS contentHash,v.state,v.chunks,v.created_at AS createdAt,v.indexed_at AS indexedAt,j.state AS jobState,j.attempts,j.next_run_at AS nextRunAt,j.last_error AS lastError FROM knowledge_version v LEFT JOIN knowledge_job j ON j.document_id=v.document_id AND j.version=v.version AND j.operation='INGEST' WHERE v.document_id=? ORDER BY v.version DESC LIMIT 100", id)); return result;
    }
    public Map<String,Object> adminVersion(String id, int version) {
        admin(); document(id, false); List<Map<String,Object>> rows = jdbc.queryForList("SELECT version,title,format,content_hash AS contentHash,content,state FROM knowledge_version WHERE document_id=? AND version=?", id, version);
        if (rows.isEmpty()) throw new ObjectiveException(404, "资料版本不存在"); Map<String,Object> result = rows.get(0);
        if ("pdf".equals(result.get("format"))) result.put("source", sourceMetadata(id, version));
        if ("video".equals(result.get("format"))) result.put("source", videoMetadata(id,version));
        admin(); return result;
    }

    private Map<String,Object> sourceMetadata(String id, int version) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT source_name AS name,source_hash AS hash,byte_length AS byteLength,pages,empty_pages AS emptyPages,extraction_mode AS extractionMode FROM knowledge_source WHERE document_id=? AND version=?", id, version);
        if (rows.isEmpty()) throw new ObjectiveException(503, "PDF 原件信息不可用，请联系管理员");
        Map<String,Object> result = rows.get(0);
        result.put("emptyPages", KnowledgePdfManager.emptyPages((String)result.get("emptyPages"), ((Number)result.get("pages")).intValue())); return result;
    }
    private Map<String,Object> videoMetadata(String id,int version){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT source_name AS name,source_hash AS hash,byte_length AS byteLength,duration_ms AS durationMs,mime,language,extraction_mode AS extractionMode FROM knowledge_video_source WHERE document_id=? AND version=?",id,version);
        if(rows.isEmpty())throw new ObjectiveException(503,"视频原件信息不可用，请联系管理员");return rows.get(0);
    }

    public static final class Context {
        public final String uid, query;
        public final List<KnowledgeDTO.Hit> hits;
        private final Map<String,Map<String,Object>> versions;
        private Context(String uid, String query, List<KnowledgeDTO.Hit> hits, Map<String,Map<String,Object>> versions) { this.uid=uid; this.query=query; this.hits=hits; this.versions=versions; }
    }
    public Map<String,Object> directory(int page,String keyword){
        String uid=uid();access.requireChat(uid);
        if(page<1||page>100000||keyword!=null&&length(keyword)>200)throw new ObjectiveException(400,"分页或搜索条件无效");
        String pattern="%"+(keyword==null?"":keyword.trim()).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String scope=maintainer()?"":" AND d.visibility='PUBLIC'";
        String from=" FROM knowledge_document d JOIN knowledge_version v ON v.document_id=d.id AND v.version=d.published_version WHERE d.state='ACTIVE' AND v.state='INDEXED'"+scope+" AND v.title LIKE ? ESCAPE '!'";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,pattern);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT d.id,d.revision,v.version,v.title,d.updated_at AS updatedAt"+from+" ORDER BY d.updated_at DESC,d.id LIMIT 20 OFFSET ?",pattern,(page-1)*20);
        if(!rows.isEmpty()){
            List<String> matches=new ArrayList<>();List<Object> args=new ArrayList<>();
            for(Map<String,Object> row:rows){matches.add("(id=? AND revision=? AND published_version=?)");args.add(row.get("id"));args.add(row.get("revision"));args.add(row.get("version"));}
            String allowed=maintainer()?"":" AND visibility='PUBLIC'";
            if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE state='ACTIVE'"+allowed+" AND ("+String.join(" OR ",matches)+")",Integer.class,args.toArray())!=rows.size())throw changed();
        }
        access.requireChat(uid);for(Map<String,Object> row:rows)row.remove("revision");Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("total",total);result.put("indexConfigured",index.configured());return result;
    }
    public Map<String,Object> search(KnowledgeDTO.Search dto) {
        String uid=uid(); access.allowKnowledgeRead(uid); return view(retrieve(uid,dto));
    }
    public Context retrieve(String uid, KnowledgeDTO.Search dto) {
        access.requireChat(uid);
        if (!Objects.equals(uid, uid())) throw new ObjectiveException(403, "不能检索其他账号的资料范围");
        if (dto == null || dto.getQuery() == null || dto.getQuery().trim().isEmpty() || length(dto.getQuery()) > 2000 || dto.getQuery().indexOf('\0') >= 0
                || dto.getTopK() == null || dto.getTopK() < 1 || dto.getTopK() > 10 || dto.getDocumentIds() != null && dto.getDocumentIds().size() > 100)
            throw new ObjectiveException(400, "请填写 2000 字以内的问题，最多返回 10 条引用");
        Set<String> filters = new LinkedHashSet<>(); if (dto.getDocumentIds()!=null) for(String id:dto.getDocumentIds()){id(id);filters.add(id);}
        if (!capacity.tryAcquire()) throw new ObjectiveException(429, "知识库检索繁忙，请稍后再试");
        try {
            List<Object> args=new ArrayList<>(); String scope=maintainer()?"":" AND d.visibility='PUBLIC'";
            if(dto.getDocumentIds()!=null) {if(filters.isEmpty()) {Context empty=new Context(uid,dto.getQuery(),Collections.emptyList(),Collections.emptyMap());verify(empty);return empty;}
                scope+=" AND d.id IN ("+String.join(",",Collections.nCopies(filters.size(),"?"))+")";args.addAll(filters);}
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT d.id,d.revision,v.version,v.content_hash,v.format FROM knowledge_document d JOIN knowledge_version v ON v.document_id=d.id AND v.version=d.published_version WHERE d.state='ACTIVE' AND v.state='INDEXED'"+scope+" ORDER BY d.id LIMIT 501",args.toArray());
            if(rows.size()>500) throw new ObjectiveException(400,"资料数量较多，请先选择要检索的资料");
            List<KnowledgeDTO.VersionRef> refs=new ArrayList<>();Map<String,Map<String,Object>> versions=new LinkedHashMap<>();
            for(Map<String,Object> row:rows){KnowledgeDTO.VersionRef ref=new KnowledgeDTO.VersionRef();ref.setDocumentId((String)row.get("id"));ref.setVersion(((Number)row.get("version")).intValue());ref.setContentHash((String)row.get("content_hash"));refs.add(ref);versions.put(ref.getDocumentId(),row);}
            access.requireChat(uid); List<KnowledgeDTO.Hit> hits=refs.isEmpty()?Collections.emptyList():index.retrieve(dto.getQuery(),refs,dto.getTopK());
            Context result=new Context(uid,dto.getQuery(),hits,versions);verify(result);return result;
        } finally {capacity.release();}
    }
    public void verify(Context context) {
        access.requireChat(context.uid);
        if (!Objects.equals(context.uid, uid())) {
            throw new ObjectiveException(403, "当前账号已变化，请重新检索");
        }
        boolean admin = maintainer();
        Map<String, List<KnowledgeDTO.Hit>> citationsByDocument = new LinkedHashMap<>();
        for (KnowledgeDTO.Hit hit : context.hits) {
            Map<String, Object> expected = context.versions.get(hit.getDocumentId());
            if (expected == null || number(expected.get("version")) != hit.getVersion()
                    || !Objects.equals(expected.get("content_hash"), hit.getContentHash())) {
                throw changed();
            }
            citationsByDocument.computeIfAbsent(hit.getDocumentId(), id -> new ArrayList<>()).add(hit);
        }
        for (Map.Entry<String, List<KnowledgeDTO.Hit>> entry : citationsByDocument.entrySet()) {
            Map<String, Object> expected = context.versions.get(entry.getKey());
            long version = number(expected.get("version"));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT d.state,d.visibility,d.revision,d.published_version,v.state AS version_state,"
                            + "v.content_hash,v.title,v.content,v.format FROM knowledge_document d "
                            + "JOIN knowledge_version v ON v.document_id=d.id AND v.version=? WHERE d.id=?",
                    version, entry.getKey());
            if (rows.isEmpty()) throw changed();
            Map<String, Object> row = rows.get(0);
            if (!"ACTIVE".equals(row.get("state")) || !"INDEXED".equals(row.get("version_state"))
                    || !admin && !"PUBLIC".equals(row.get("visibility"))
                    || row.get("published_version") == null || number(row.get("published_version")) != version
                    || number(row.get("revision")) != number(expected.get("revision"))
                    || !Objects.equals(row.get("content_hash"), expected.get("content_hash"))) {
                throw changed();
            }
            verifyExcerpts(row, entry.getValue());
        }
        verifyCitationPublications(context, citationsByDocument.keySet());
        access.requireChat(context.uid);
    }

    private void verifyCitationPublications(Context context, Set<String> documents) {
        if (documents.isEmpty()) return;
        List<String> matches = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String id : documents) {
            Map<String, Object> expected = context.versions.get(id);
            matches.add("(d.id=? AND d.revision=? AND v.version=? AND v.content_hash=?)");
            args.add(id);
            args.add(expected.get("revision"));
            args.add(expected.get("version"));
            args.add(expected.get("content_hash"));
        }
        String scope = maintainer() ? "" : " AND d.visibility='PUBLIC'";
        // All returned sources must still match in the same database snapshot after excerpt checks.
        Integer current = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document d JOIN knowledge_version v "
                        + "ON v.document_id=d.id AND v.version=d.published_version "
                        + "WHERE d.state='ACTIVE' AND v.state='INDEXED'" + scope
                        + " AND (" + String.join(" OR ", matches) + ")",
                Integer.class, args.toArray());
        if (current == null || current != documents.size()) throw changed();
    }

    private void verifyExcerpts(Map<String, Object> source, List<KnowledgeDTO.Hit> citations) {
        String content = (String) source.get("content");
        String format = (String) source.get("format");
        String[] pages = "pdf".equals(format) ? content.split("\n\f\n", -1) : null;
        VideoTranscript transcript = null;
        if ("video".equals(format)) {
            try {
                transcript = VideoTranscript.parse(content);
            } catch (IllegalArgumentException error) {
                throw new ObjectiveException(502, "视频引用时间与正文不符，请重新检索");
            }
        }
        for (KnowledgeDTO.Hit hit : citations) {
            String excerpt = hit.getExcerpt();
            if (!Objects.equals(source.get("title"), hit.getTitle()) || excerpt == null || excerpt.trim().isEmpty()) {
                throw new ObjectiveException(502, "知识库引用正文校验失败，请重新检索");
            }
            hit.setStartMs(null);
            hit.setEndMs(null);
            hit.setPage(null);
            hit.setSourceStart(null);
            hit.setSourceEnd(null);
            if (transcript != null) {
                try {
                    long[] bounds = transcript.citation(hit.getHeadingPath(), excerpt);
                    hit.setStartMs(bounds[0]);
                    hit.setEndMs(bounds[1]);
                } catch (IllegalArgumentException error) {
                    throw new ObjectiveException(502, "视频引用时间与正文不符，请重新检索");
                }
            } else if (pages != null) {
                String heading = hit.getHeadingPath();
                if (heading == null || !heading.matches("第 [1-9][0-9]{0,2} 页")) {
                    throw new ObjectiveException(502, "PDF 引用页号无效");
                }
                int page = Integer.parseInt(heading.substring(2, heading.length() - 2));
                if (page > pages.length || page > 200 || !pages[page - 1].contains(excerpt)) {
                    throw new ObjectiveException(502, "PDF 引用页号与正文不符");
                }
                hit.setPage(page);
            } else {
                int start = content.indexOf(excerpt);
                if (start < 0) {
                    throw new ObjectiveException(502, "知识库引用正文校验失败，请重新检索");
                }
                // Java and browser strings share UTF-16 offsets. Repeated excerpts have no unique target.
                if (("markdown".equals(format) || "text".equals(format)) && content.indexOf(excerpt, start + 1) < 0) {
                    hit.setSourceStart(start);
                    hit.setSourceEnd(start + excerpt.length());
                }
            }
        }
    }

    private ObjectiveException changed(){return new ObjectiveException(409,"资料已更新或撤回，请重新检索");}
    public Map<String,Object> view(Context context) {
        verify(context);List<Map<String,Object>> items=new ArrayList<>();int i=0;
        for(KnowledgeDTO.Hit hit:context.hits){Map<String,Object> item=new LinkedHashMap<>();item.put("citationId","K"+(++i));item.put("documentId",hit.getDocumentId());item.put("version",hit.getVersion());item.put("chunkId",hit.getChunkId());item.put("title",hit.getTitle());item.put("headingPath",hit.getHeadingPath());item.put("excerpt",hit.getExcerpt());item.put("score",hit.getScore());String url="/knowledge/"+hit.getDocumentId()+"?version="+hit.getVersion()+"&chunk="+hit.getChunkId();if(hit.getPage()!=null){item.put("page",hit.getPage());url+="&page="+hit.getPage();}item.put("url",url);items.add(item);}
        for(int position=0;position<items.size();position++){
            Map<String,Object> item=items.get(position);KnowledgeDTO.Hit hit=context.hits.get(position);
            if (hit.getSourceStart() != null) {
                item.put("sourceStart", hit.getSourceStart());
                item.put("sourceEnd", hit.getSourceEnd());
                item.put("url", item.get("url") + "&from=" + hit.getSourceStart() + "&to=" + hit.getSourceEnd());
            }
            if(hit.getStartMs()!=null){item.put("startMs",hit.getStartMs());item.put("endMs",hit.getEndMs());item.put("url",item.get("url")+"&t="+java.math.BigDecimal.valueOf(hit.getStartMs(),3).stripTrailingZeros().toPlainString());}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("modelUsed",false);result.put("content",null);return result;
    }
    public Map<String,Object> read(String id,Integer version) {
        String uid=uid();access.requireChat(uid);Map<String,Object> doc=document(id,false);active(doc);
        if(doc.get("published_version")==null || !maintainer()&&!"PUBLIC".equals(doc.get("visibility")) || version!=null&&version.longValue()!=number(doc.get("published_version"))) throw new ObjectiveException(404,"资料不存在或已撤回");
        Map<String,Object> row=jdbc.queryForMap("SELECT version,title,format,content_hash,content,state FROM knowledge_version WHERE document_id=? AND version=?",id,doc.get("published_version"));
        if(!"INDEXED".equals(row.get("state"))) throw new ObjectiveException(404,"资料不存在或已撤回");
        Map<String,Object> source="pdf".equals(row.get("format"))?sourceMetadata(id,((Number)row.get("version")).intValue()):null;
        if("video".equals(row.get("format")))source=videoMetadata(id,((Number)row.get("version")).intValue());
        Map<String,Object> current=document(id,false);if(!Objects.equals(doc.get("revision"),current.get("revision"))) throw changed();access.requireChat(uid);
        Map<String,Object> result=new LinkedHashMap<>();for(String key:Arrays.asList("version","title","format","content"))result.put(key,row.get(key));result.put("id",id);if(source!=null)result.put("source",source);return result;
    }
}
