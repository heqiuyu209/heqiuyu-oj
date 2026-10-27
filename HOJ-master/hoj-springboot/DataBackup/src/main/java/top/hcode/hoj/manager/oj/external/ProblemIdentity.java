package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Locale;
import java.util.regex.*;

/** Only explicit platform IDs establish identity. Similar titles never do. */
public final class ProblemIdentity {
    private ProblemIdentity() {}
    private static final Pattern LOCAL_CF = Pattern.compile("(?:CF|GYM)-([0-9]+)([A-Za-z][A-Za-z0-9]{0,15})");
    public static String nowcoder(URI url) {
        if(!"https".equals(url.getScheme()) || url.getRawQuery()!=null || url.getRawFragment()!=null || url.getRawUserInfo()!=null || url.getPort()!=-1)throw new IllegalArgumentException("Invalid NowCoder source ID");
        String path=url.getRawPath();Matcher match;
        if("ac.nowcoder.com".equals(url.getHost())) {
            match=Pattern.compile("/acm/problem/([1-9][0-9]{0,11})").matcher(path);
            if(match.matches())return "nowcoder:acm:"+match.group(1);
            match=Pattern.compile("/acm/contest/([1-9][0-9]{0,11})/([A-Za-z][A-Za-z0-9]{0,15})").matcher(path);
            if(match.matches())return "nowcoder:contest:"+match.group(1)+":"+match.group(2).toUpperCase(Locale.ROOT);
        }else if("www.nowcoder.com".equals(url.getHost())) {
            match=Pattern.compile("/practice/([a-fA-F0-9]{32})").matcher(path);
            if(match.matches())return "nowcoder:practice:"+match.group(1).toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Unknown NowCoder source ID");
    }
    public static String atcoder(String taskId) {
        if(taskId==null || !taskId.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid AtCoder task ID");
        return "atcoder:task:"+taskId;
    }
    public static String codeforces(JsonNode problem) {
        String index = problem.path("index").asText("");
        if (!index.matches("[A-Za-z0-9]{1,16}")) throw new IllegalArgumentException("Invalid problem index");
        if (problem.path("contestId").isIntegralNumber() && problem.path("contestId").canConvertToLong() && problem.path("contestId").asLong() > 0)
            return "cf:contest:" + problem.path("contestId").asLong() + ":" + index.toUpperCase(Locale.ROOT);
        String set = problem.path("problemsetName").asText("");
        if (!set.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Missing source identity");
        return "cf:set:" + set.toLowerCase(Locale.ROOT) + ":" + index.toUpperCase(Locale.ROOT);
    }
    public static String local(long id, String displayId, boolean remote) {
        if(remote && displayId!=null && displayId.startsWith("NC-")) {
            String value=displayId.substring(3),path;
            if(value.startsWith("PRACTICE-"))path="https://www.nowcoder.com/practice/"+value.substring(9);
            else if(value.matches("[1-9][0-9]{0,11}-[A-Za-z][A-Za-z0-9]{0,15}"))path="https://ac.nowcoder.com/acm/contest/"+value.replace('-','/');
            else path="https://ac.nowcoder.com/acm/problem/"+value;
            try{return nowcoder(URI.create(path));}catch(IllegalArgumentException ignored){/* Unknown formats stay local. */}
        }
        if(remote && displayId!=null && displayId.startsWith("AC-")) {
            try { return atcoder(displayId.substring(3)); } catch(IllegalArgumentException ignored) { /* Unknown formats stay local. */ }
        }
        Matcher match = LOCAL_CF.matcher(displayId == null ? "" : displayId);
        if (remote && match.matches()) {
            try {
                long contest = Long.parseLong(match.group(1));
                if (contest > 0) return "cf:contest:" + contest + ":" + match.group(2).toUpperCase(Locale.ROOT);
            } catch (NumberFormatException ignored) { /* Unknown source IDs remain separate local problems. */ }
        }
        return "hoj:" + id;
    }
}
