package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.manager.oj.*;
import top.hcode.hoj.pojo.dto.AiDTO;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.vo.ProblemInfoVO;
import java.security.MessageDigest;
import java.util.*;

/** A statement-only projection from existing authorized readers, never browser-supplied content. */
@Component
public class AiProblemContextManager {
    @Autowired private ProblemManager problems;
    @Autowired private ContestManager contests;
    @Autowired private ObjectiveManager objectives;
    @Autowired private PaperManager papers;
    @Autowired private AiAccessGuard access;
    @Autowired private ObjectMapper json;

    public static final class Context {
        public final AiDTO.ProblemReference reference;
        public final Map<String,Object> material;
        public final String fingerprint;
        private Context(AiDTO.ProblemReference reference, Map<String,Object> material, String fingerprint) { this.reference=reference;this.material=material;this.fingerprint=fingerprint; }
        public Map<String,Object> view() { Map<String,Object> result=new LinkedHashMap<>();result.put("reference",reference);result.put("material",material);result.put("fingerprint",fingerprint);return result; }
    }
    private void require(boolean condition) { if(!condition)throw new ObjectiveException(400,"题目上下文标识无效，请重新选择题目"); }
    private boolean positive(Long value) { return value!=null&&value>0; }
    private String text(Object value) { return value==null?"":String.valueOf(value); }
    private void put(Map<String,Object> target,String key,Object value) {
        String content=text(value);
        if(content.length()>24000)throw new ObjectiveException(400,"题面过长，请取消附带题面，摘取需要讨论的部分后提问");
        target.put(key,content);
    }
    public Context read(String uid,AiDTO.ProblemReference supplied) {
        access.requireChat(uid);require(supplied!=null);
        AiDTO.ProblemReference ref=new AiDTO.ProblemReference();ref.setKind(supplied.getKind());
        Map<String,Object> material=new LinkedHashMap<>();Long originProblemId=null;
        try {
            if("PROGRAMMING".equals(ref.getKind())) {
                require(supplied.getProblemId()!=null&&!supplied.getProblemId().trim().isEmpty()&&supplied.getProblemId().length()<=100&&supplied.getProblemId().indexOf('\0')<0);
                require(supplied.getRunId()==null&&supplied.getQuestionKey()==null);
                require(supplied.getGroupId()==null||positive(supplied.getGroupId()));require(supplied.getContestId()==null||positive(supplied.getContestId()));
                ref.setProblemId(supplied.getProblemId());ref.setGroupId(supplied.getGroupId());ref.setContestId(supplied.getContestId());
                ProblemInfoVO view=ref.getContestId()==null?problems.getProblemInfo(ref.getProblemId(),ref.getGroupId()):contests.getContestProblemDetails(ref.getContestId(),ref.getProblemId(),true);
                Problem p=view.getProblem();if(p.getId()==null)throw new ObjectiveException(503,"题目标识暂不可用");
                originProblemId=p.getId();
                material.put("questionType","PROGRAMMING");put(material,"title",p.getTitle());put(material,"source",p.getSource());put(material,"displayId",ref.getProblemId());
                put(material,"stem",p.getDescription());put(material,"input",p.getInput());put(material,"output",p.getOutput());put(material,"examples",p.getExamples());
                material.put("timeLimitMs",p.getTimeLimit());material.put("memoryLimitMiB",p.getMemoryLimit());
            } else {
                require(Arrays.asList("OBJECTIVE_RUN","PAPER_RUN").contains(ref.getKind()));require(positive(supplied.getRunId()));
                require(supplied.getProblemId()==null&&supplied.getGroupId()==null&&supplied.getContestId()==null);
                require(supplied.getQuestionKey()!=null&&supplied.getQuestionKey().length()<=50);
                if("OBJECTIVE_RUN".equals(ref.getKind()))require(supplied.getQuestionKey().matches("[1-9][0-9]{0,18}"));
                else require(supplied.getQuestionKey().matches("(PROGRAMMING|OBJECTIVE):[1-9][0-9]{0,18}"));
                ref.setRunId(supplied.getRunId());ref.setQuestionKey(supplied.getQuestionKey());
                Map<String,Object> run="OBJECTIVE_RUN".equals(ref.getKind())?objectives.run(ref.getRunId()):papers.run(ref.getRunId());
                @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)run.get("OBJECTIVE_RUN".equals(ref.getKind())?"questions":"items");
                Map<String,Object> item=items.stream().filter(row->ref.getQuestionKey().equals(text(row.get("OBJECTIVE_RUN".equals(ref.getKind())?"id":"key")))).findFirst().orElseThrow(()->new ObjectiveException(404,"题目不属于当前答卷"));
                for(String field:Arrays.asList("title","source","stem","input","output","examples","questionType"))if(item.containsKey(field))put(material,field,item.get(field));
                material.put("version",item.get("version"));put(material,"displayId",item.getOrDefault("problemId",ref.getQuestionKey()));
                // Explicitly copy each public field; never forward a whole option/blank map or an answer flag.
                List<Map<String,Object>> options=new ArrayList<>();
                Object original=item.get("options");
                if(original instanceof Collection<?>)for(Object option:(Collection<?>)original) {
                    Map<?,?> row=json.convertValue(option,Map.class);Map<String,Object> value=new LinkedHashMap<>();put(value,"id",row.get("id"));put(value,"text",row.get("text"));options.add(value);
                }
                material.put("options",options);
                List<Map<String,Object>> blanks=new ArrayList<>();original=item.get("blanks");
                if(original instanceof Collection<?>)for(Object blank:(Collection<?>)original) {
                    Map<?,?> row=json.convertValue(blank,Map.class);Map<String,Object> value=new LinkedHashMap<>();put(value,"id",row.get("id"));put(value,"label",row.get("label"));blanks.add(value);
                }
                material.put("blanks",blanks);
            }
        } catch(StatusNotFoundException e) { throw new ObjectiveException(404,"题目不存在或已不可访问"); }
        catch(StatusForbiddenException e) { throw new ObjectiveException(403,"当前无权读取该题目"); }
        catch(StatusFailException e) { throw new ObjectiveException(403,"当前无法访问该题目"); }
        try {
            byte[] body=json.writeValueAsBytes(Arrays.asList(ref,material,originProblemId));
            if(body.length>65536||json.writeValueAsString(material).length()>24000)throw new ObjectiveException(400,"题面过长，请取消附带题面，摘取需要讨论的部分后提问");
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(body);StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format("%02x",value&255));
            access.requireChat(uid);return new Context(ref,material,hex.toString());
        } catch(ObjectiveException e) { throw e; } catch(Exception e) { throw new ObjectiveException(503,"题面上下文暂不可用"); }
    }
    public Context confirmed(String uid,AiDTO.ProblemContext dto) {
        require(dto!=null&&dto.getFingerprint()!=null&&dto.getFingerprint().matches("[a-f0-9]{64}"));
        Context context=read(uid,dto.getReference());
        if(!context.fingerprint.equals(dto.getFingerprint()))throw new ObjectiveException(409,"题面或访问权限已变化，请刷新题面预览后重新提问");
        return context;
    }
    public void verify(String uid,Context context) {
        if(!context.fingerprint.equals(read(uid,context.reference).fingerprint))throw new ObjectiveException(409,"题面或访问权限已变化，请刷新题面预览后重新提问");
    }
}
