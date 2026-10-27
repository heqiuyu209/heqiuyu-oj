package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.net.URI;
import java.util.*;
import static top.hcode.hoj.manager.oj.external.NowCoderDataClient.integer;

/** Public completed-contest context only. Neither source cookies nor private/team submissions are requested. */
@Component
public class NowCoderContestClient {
    @Autowired private NowCoderDataClient transport;
    @Autowired private ObjectMapper json;
    static final int LIST_SIZE=10, PROBLEM_SIZE=100, STATUS_SIZE=20, MAX_PAGES=5000;
    protected long now(){return System.currentTimeMillis();}
    private ObjectiveException invalid(){return new ObjectiveException(502,"牛客比赛上下文暂不可用或格式变化，保留上次完整记录");}
    protected URI endpoint(String stage,String uid,long contest,int page){
        NowCoderDataClient.handle(uid);
        if(page<1 || page>MAX_PAGES || contest<0)throw new IllegalArgumentException("Invalid context cursor");
        if("LIST".equals(stage)||"VERIFY_LIST".equals(stage))return URI.create("https://ac.nowcoder.com/acm-heavy/acm/contest/profile/contest-joined-history?uid="+uid+"&page="+("VERIFY_LIST".equals(stage)?1:page)+"&onlyJoinedFilter=true&searchContestName=&onlyRatingFilter=false&contestEndFilter=true");
        if(contest<1 || contest>999999999999L)throw new IllegalArgumentException("Invalid contest ID");
        if("PROBLEMS".equals(stage)||"VERIFY_PROBLEMS".equals(stage))return URI.create("https://ac.nowcoder.com/acm/contest/problem-list?id="+contest+"&page="+("VERIFY_PROBLEMS".equals(stage)?1:page)+"&pageSize=100");
        if("STATUS".equals(stage)||"VERIFY_STATUS".equals(stage))return URI.create("https://ac.nowcoder.com/acm-heavy/acm/contest/status-list?id="+contest+"&page="+("VERIFY_STATUS".equals(stage)?1:page)+"&pageSize=20&searchUserName="+uid+"&contestTypeList=contest");
        throw new IllegalArgumentException("Invalid context stage");
    }
    public JsonNode fetch(Map<String,Object> job){
        String stage=(String)job.get("stage"),uid=(String)job.get("handle");int page=((Number)job.get("next_page")).intValue();
        long cid=job.get("contest_id")==null?0:((Number)job.get("contest_id")).longValue();
        try {
            JsonNode root=json.readTree(transport.publicText(endpoint(stage,uid,cid,page)));
            if("LIST".equals(stage)||"VERIFY_LIST".equals(stage))return contests(root,uid,"VERIFY_LIST".equals(stage)?1:page);
            if("PROBLEMS".equals(stage)||"VERIFY_PROBLEMS".equals(stage))return problems(root,cid,"VERIFY_PROBLEMS".equals(stage)?1:page);
            return submissions(root,uid,cid,"VERIFY_STATUS".equals(stage)?1:page);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}
    }
    private JsonNode data(JsonNode root){
        if(root==null || integer(root.get("code"),0,0)!=0 || !root.path("data").isObject())throw invalid();return root.path("data");
    }
    private String text(JsonNode node,int limit){
        if(node==null || !node.isTextual() || node.asText().trim().isEmpty() || node.asText().codePointCount(0,node.asText().length())>limit || node.asText().indexOf('\0')>=0)throw invalid();return node.asText();
    }
    private boolean bool(JsonNode node){if(node==null || !node.isBoolean())throw invalid();return node.asBoolean();}
    private int pagination(JsonNode page,int expected,int size,String totalField,int returned){
        int current=(int)integer(page.get("pageCurrent"),1,MAX_PAGES),total=(int)integer(page.get(totalField),0,(long)size*MAX_PAGES);
        int pages=(int)integer(page.get("pageCount"),0,MAX_PAGES);
        if(current!=expected || integer(page.get("pageSize"),size,size)!=size || total>0 && pages!=(total+size-1)/size || total==0 && pages>1
                || expected>Math.max(1,pages) || returned!=Math.min(size,Math.max(0,total-(expected-1)*size)))throw invalid();
        return total;
    }
    JsonNode contests(JsonNode root,String uid,int page){
        NowCoderDataClient.handle(uid);JsonNode value=data(root),items=value.path("dataList"),info=value.path("pageInfo");
        if(!items.isArray())throw invalid();int total=pagination(info,page,LIST_SIZE,"totalCount",items.size());
        if(integer(info.get("elementCount"),0,LIST_SIZE)!=items.size())throw invalid();
        ArrayNode rows=json.createArrayNode();Set<Long> ids=new HashSet<>();
        for(JsonNode row:items){
            long cid=integer(row.get("contestId"),1,999999999999L),start=integer(row.get("startTime"),1,now()),end=integer(row.get("endTime"),start,now());
            boolean team=bool(row.get("isTeamSignUp"));long identity=integer(row.get("teamId"),1,999999999999L);
            if(!ids.add(cid) || !team && !uid.equals(Long.toString(identity)))throw invalid();
            String participation=text(row.get("participationType"),24);
            // The official profile UI explicitly identifies STAR participation.
            // Do not invent virtual/practice categories from submission times.
            if(!Arrays.asList("OFFICIAL","STAR").contains(participation))throw invalid();
            ObjectNode record=rows.addObject().put("contestId",cid).put("title",text(row.get("contestName"),255)).put("startMs",start).put("endMs",end)
                    .put("team",team).put("participation",participation).put("restricted",bool(row.path("settingInfo").get("forceOnlyMyStatusFilter")));
            // Source aggregate score is separate from our per-submission union.
            boolean visible=bool(row.get("canShowRank"));
            if(visible && row.hasNonNull("acceptedCount"))record.put("officialAccepted",integer(row.get("acceptedCount"),0,Integer.MAX_VALUE));else record.putNull("officialAccepted");
            if(visible && row.hasNonNull("rank")){long rank=integer(row.get("rank"),0,Integer.MAX_VALUE);if(rank>0)record.put("rank",rank);else record.putNull("rank");}else record.putNull("rank");
        }
        return json.createObjectNode().put("uid",uid).put("page",page).put("total",total).put("pages",(total+LIST_SIZE-1)/LIST_SIZE).set("items",rows);
    }
    JsonNode problems(JsonNode root,long cid,int page){
        JsonNode value=data(root),items=value.path("data"),info=value.path("basicInfo");
        if(!items.isArray() || integer(info.get("contestId"),cid,cid)!=cid)throw invalid();
        int total=pagination(info,page,PROBLEM_SIZE,"problemCount",items.size());ArrayNode rows=json.createArrayNode();Set<String> indexes=new HashSet<>();Set<Long> ids=new HashSet<>();
        for(JsonNode row:items){
            String index=text(row.get("index"),16);long pid=integer(row.get("problemId"),1,999999999999L);
            if(!index.matches("[A-Z][A-Z0-9]{0,15}") || !indexes.add(index) || !ids.add(pid))throw invalid();
            rows.addObject().put("problemKey","nowcoder:acm:"+pid).put("index",index).put("title",text(row.get("title"),255));
        }
        return json.createObjectNode().put("contestId",cid).put("page",page).put("total",total).put("pages",(total+PROBLEM_SIZE-1)/PROBLEM_SIZE).set("items",rows);
    }
    JsonNode submissions(JsonNode root,String uid,long cid,int page){
        NowCoderDataClient.handle(uid);JsonNode value=data(root),items=value.path("data"),info=value.path("basicInfo");
        if(!items.isArray() || !bool(value.get("isContestFinished")) || integer(info.get("contestId"),cid,cid)!=cid
                || !uid.equals(text(info.get("searchUserName"),12)))throw invalid();
        int total=pagination(info,page,STATUS_SIZE,"statusCount",items.size());ArrayNode rows=json.createArrayNode();Set<Long> ids=new HashSet<>();long previous=Long.MAX_VALUE;
        for(JsonNode row:items){
            long sid=integer(row.get("submissionId"),1,999999999999999999L),pid=integer(row.get("problemId"),1,999999999999L),stamp=integer(row.get("submitTime"),1,now()+300000);
            if(!uid.equals(Long.toString(integer(row.get("userId"),1,999999999999L))) || bool(row.get("isTeam")) || !ids.add(sid) || stamp>previous)throw invalid();previous=stamp;
            String index=text(row.get("index"),16);if(!index.matches("[A-Z][A-Z0-9]{0,15}"))throw invalid();
            rows.addObject().put("id",sid).put("problemKey","nowcoder:acm:"+pid).put("index",index).put("verdict",NowCoderDataClient.verdict(text(row.get("statusMessage"),32))).put("submittedMs",stamp);
        }
        return json.createObjectNode().put("uid",uid).put("contestId",cid).put("page",page).put("total",total).put("pages",(total+STATUS_SIZE-1)/STATUS_SIZE).set("items",rows);
    }
}
