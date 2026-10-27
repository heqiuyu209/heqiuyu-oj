package top.hcode.hoj.remote;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import org.jsoup.Jsoup;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static top.hcode.hoj.remote.RemoteSourceFailure.Kind.*;

/** Typed new-editor protocol. Tokens live only in this operation and never belong in the task journal. */
public final class NowCoderJudgeProtocol {
    public static final class Context {
        public final String userId,questionId,problemId,contestId,languageId,languageName;
        public Context(String userId,String questionId,String problemId,String contestId,String languageId,String languageName){
            if(!digits(userId,12)||!digits(questionId,16)||!digits(problemId,12)||contestId==null||!contestId.isEmpty()&&!digits(contestId,12)||!digits(languageId,9)||languageName==null||languageName.trim().isEmpty()||languageName.length()>200)throw new IllegalArgumentException("Invalid source task context");
            this.userId=userId;this.questionId=questionId;this.problemId=problemId;this.contestId=contestId;this.languageId=languageId;this.languageName=languageName;
        }
        public Map<String,String> snapshot(){
            Map<String,String> result=new LinkedHashMap<>();result.put("userId",userId);result.put("questionId",questionId);result.put("problemId",problemId);result.put("contestId",contestId);result.put("languageId",languageId);result.put("languageName",languageName);return Collections.unmodifiableMap(result);
        }
        public static Context from(Map<String,String> value){
            if(value==null || value.size()!=6)throw new IllegalArgumentException("Invalid source snapshot");return new Context(value.get("userId"),value.get("questionId"),value.get("problemId"),value.get("contestId"),value.get("languageId"),value.get("languageName"));
        }
        public String signature(){StringBuilder value=new StringBuilder();for(String field:snapshot().values())value.append(field.length()).append(':').append(field);return PersonalRemoteTaskStore.hash(value.toString());}
        @Override public String toString(){return "NowCoderContext[redacted]";}
    }
    public static final class Acknowledgement {
        public final String jobId;
        public final Long submissionId;
        public final String contextHash,codeHash;
        private Acknowledgement(String jobId,Long submissionId,String contextHash,String codeHash){this.jobId=jobId;this.submissionId=submissionId;this.contextHash=contextHash;this.codeHash=codeHash;}
        @Override public String toString(){return "NowCoderAcknowledgement[redacted]";}
    }
    public static final class Status {
        public final String jobId;
        public final Long submissionId;
        public final Integer verdict;
        public final String contextHash;
        private Status(String jobId,Long submissionId,Integer verdict,String contextHash){this.jobId=jobId;this.submissionId=submissionId;this.verdict=verdict;this.contextHash=contextHash;}
        @Override public String toString(){return "NowCoderStatus[redacted]";}
    }
    private final PersonalRemoteTransport http;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public NowCoderJudgeProtocol(PersonalRemoteTransport http){this.http=Objects.requireNonNull(http);}
    private static boolean digits(String value,int length){return value!=null && value.matches("[1-9][0-9]{0,"+(length-1)+"}");}
    private JsonNode read(String body){try{JsonNode result=json.readTree(body);if(result==null || !result.isObject())throw new RemoteSourceFailure(FORMAT_CHANGED);return result;}catch(RemoteSourceFailure e){throw e;}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}}
    private static long integer(JsonNode node,long min,long max){
        if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong()<min || node.asLong()>max)throw new RemoteSourceFailure(FORMAT_CHANGED);return node.asLong();
    }
    private JsonNode data(String body){
        JsonNode root=read(body);long code=integer(root.get("code"),Integer.MIN_VALUE,Integer.MAX_VALUE);
        if(code==998 || code==999)throw new RemoteSourceFailure(AUTH_REQUIRED);
        if(code!=0 || !root.path("data").isObject())throw new RemoteSourceFailure(FORMAT_CHANGED);return root.path("data");
    }
    private String accessToken(Context context){
        RemoteSessionIdentity.require("NOWCODER",context.userId,Jsoup.parse(http.get("/")));
        JsonNode root=read(http.nowcoderAccessToken());
        if(!root.path("success").isBoolean() || !root.path("success").asBoolean())throw new RemoteSourceFailure(AUTH_REQUIRED);
        JsonNode value=root.path("data").path("accessToken");if(!value.isTextual())throw new RemoteSourceFailure(FORMAT_CHANGED);
        String token=value.asText().replaceFirst("(?i)^Bearer\\s+","");
        if(token.isEmpty() || token.length()>4096 || !token.matches("[A-Za-z0-9._~+/-]+=*"))throw new RemoteSourceFailure(FORMAT_CHANGED);return token;
    }
    private String remark(Context context){return context.contestId.isEmpty()?"{}":"{\"contestId\":"+context.contestId+"}";}
    private static String jobId(JsonNode value){
        if(value==null || !(value.isTextual() || value.isIntegralNumber()))throw new RemoteSourceFailure(FORMAT_CHANGED);
        String id=value.asText();if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new RemoteSourceFailure(FORMAT_CHANGED);return id;
    }
    private static Long submissionId(JsonNode value){
        if(value==null || value.isNull())return null;
        if(value.isIntegralNumber())return integer(value,1,Long.MAX_VALUE);
        if(value.isTextual() && value.asText().matches("[1-9][0-9]{0,18}"))try{return Long.parseLong(value.asText());}catch(NumberFormatException ignored){}
        throw new RemoteSourceFailure(FORMAT_CHANGED);
    }
    /** The caller must atomically journal beginPost before allowing this operation to send its one code POST. */
    public Acknowledgement submit(Context context,String code,Runnable beginPost){
        Objects.requireNonNull(context);Objects.requireNonNull(beginPost);
        if(code==null || code.trim().isEmpty() || code.getBytes(StandardCharsets.UTF_8).length>65535)throw new IllegalArgumentException("Invalid source code");
        String token=accessToken(context);ObjectNode request=json.createObjectNode().put("content",code).put("questionId",context.questionId).put("language",context.languageId)
                .put("tagId",4).put("appId",6).put("userId",context.userId).put("submitType",1).put("remark",remark(context)).put("token",token).put("showId",6);
        JsonNode result=data(http.nowcoderSubmit(request.toString(),beginPost));
        return new Acknowledgement(jobId(result.get("id")),submissionId(result.get("submissionId")),context.signature(),PersonalRemoteTaskStore.hash(code));
    }
    /** A job ID is distinct from the public submission ID; neither can be inferred from the other. */
    public Status status(Context context,String expectedJob){
        if(expectedJob==null || !expectedJob.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid source job ID");
        String token=accessToken(context);Map<String,String> query=new LinkedHashMap<>();
        query.put("id",expectedJob);query.put("userId",context.userId);query.put("questionId",context.questionId);query.put("language",context.languageId);
        query.put("appId","6");query.put("tagId","4");query.put("submitType","1");query.put("remark",remark(context));query.put("token",token);query.put("showId","6");
        JsonNode result=data(http.nowcoderStatus(query));
        if(result.has("id") && !expectedJob.equals(jobId(result.get("id"))))throw new RemoteSourceFailure(AMBIGUOUS_RESULT);
        for(String field:Arrays.asList("userId","questionId"))if(result.has(field) && !query.get(field).equals(result.path(field).asText()))throw new RemoteSourceFailure(AMBIGUOUS_RESULT);
        if(result.has("language") && !context.languageId.equals(result.path("language").asText()) && !context.languageName.equals(result.path("language").asText()))throw new RemoteSourceFailure(AMBIGUOUS_RESULT);
        if(result.has("isSelfTest") && (!result.path("isSelfTest").isBoolean() || result.path("isSelfTest").asBoolean()))throw new RemoteSourceFailure(FORMAT_CHANGED);
        int sourceStatus=(int)integer(result.get("status"),0,200);Integer verdict;
        switch(sourceStatus){
            case 0:case 1:case 2:case 107:verdict=null;break;
            case 5:verdict=0;break;
            case 6:verdict=1;break;
            case 12:verdict=-2;break;
            case 14:verdict=4;break;
            case 4:
                String description=result.path("judgeReplyDesc").asText("");
                switch(description){case "答案错误":verdict=-1;break;case "运行错误":case "输出超限":verdict=3;break;case "内存超限":verdict=2;break;case "格式错误":verdict=-3;break;default:throw new RemoteSourceFailure(FORMAT_CHANGED);}break;
            default:throw new RemoteSourceFailure(FORMAT_CHANGED);
        }
        Long submission=submissionId(result.get("submissionId"));
        if(verdict!=null && submission==null)throw new RemoteSourceFailure(FORMAT_CHANGED);
        if(sourceStatus==5 && result.has("rightHundredRate")){
            JsonNode rate=result.get("rightHundredRate");if(!rate.isNumber() || rate.decimalValue().compareTo(java.math.BigDecimal.valueOf(100))!=0)throw new RemoteSourceFailure(FORMAT_CHANGED);
        }
        return new Status(expectedJob,submission,verdict,context.signature());
    }
}
